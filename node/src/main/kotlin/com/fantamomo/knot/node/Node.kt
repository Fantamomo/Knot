package com.fantamomo.knot.node

import com.fantamomo.knot.shared.manager.TunnelManager
import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.Network
import com.fantamomo.knot.shared.utils.switch
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

object Node {
    private val logger = LoggerFactory.getLogger(Node::class.java)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val started = atomic(false)

    lateinit var config: NodeConfig
        private set

    val configDir: Path get() = config.configDir

    lateinit var network: Network
        private set
    lateinit var tunnelManager: TunnelManager
        private set

    private class KnownPeer(val id: PeerId, val addresses: List<Pair<String, Int>>)

    suspend fun start(args: Array<String>) {
        if (!started.compareAndSet(
                expect = false,
                update = true
            )
        ) throw IllegalStateException("Node is already running")

        config = NodeConfig.load(args)

        logger.info("Starting node (config: {})", config)

        try {
            scope.switch {
                run()
            }
        } finally {
            scope.cancel()
        }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun run(): Nothing = coroutineScope {
        val dir = config.configDir
        val caFile = requireFile(dir.resolve(config.caFileName), "CA certificate")
        val p12File = requireFile(dir.resolve(config.keyStoreFileName), "Node certificate")
        val p12PasswordFile = requireFile(dir.resolve(config.keyStorePasswordFileName), "Node certificate password")
        val peerIdFile = requireFile(dir.resolve(config.peerIdFileName), "Node peer ID")
        val localPeerId = PeerId.fromHexString(peerIdFile.readText().trim())

        network = Network(
            scope = scope,
            host = config.host,
            port = config.port,
            localPeerId = localPeerId,
            keyStoreFile = p12File,
            keyStorePassword = p12PasswordFile.readText().trim().toCharArray(),
            caCertificateInput = caFile,
            onPeerConnected = ::onPeerConnected
        )

        network.start()

        tunnelManager = TunnelManager(network)

        launch { tunnelManager.start() }

        val bootstrapNode = config.bootstrapNode
        if (bootstrapNode != null) {
            try {
                val host = bootstrapNode.split(":")[0]
                val port = bootstrapNode.split(":")[1].toInt()
                launch { network.connectTo(host, port) }
            } catch (e: Exception) {
                logger.error("Failed to connect to bootstrap node: $bootstrapNode", e)
            }
        }

        for (peer in loadKnownPeers(dir.resolve(config.knownPeersFileName))) {
            if (peer.id == localPeerId) continue
            launch { maintainConnection(peer) }
        }

        if (System.getenv("OPEN_TUNNEL") == "true") {
            launch {
                delay(5.seconds)
                logger.info("Opening tunnel")
                tunnelManager.openTunnel(network.peers.keys.first(), "localhost", 8000, "0.0.0.0", 8888)
            }
        }

        awaitCancellation()
    }

    private fun onPeerConnected(peerId: PeerId) {
        logger.info("Peer connected: {}", peerId)

        scope.launch {
            delay(5.seconds)

//            val peer = network.peers[peerId] ?: return@launch

            val addresses = buildList {
//                add(peer.client.transport.remoteAddr.hostAddress to peer.client.transport.remotePort)

                addAll(network.router.knownAddresses(peerId))
            }

            saveKnownPeer(peerId, addresses)
        }
    }

    private fun saveKnownPeer(
        peerId: PeerId,
        newAddresses: List<Pair<String, Int>>
    ) {
        val file = config.configDir.resolve(config.knownPeersFileName)

        synchronized(this@Node) {
            try {
                file.parent?.takeUnless(Path::exists)?.createDirectories()

                val lines = if (file.exists()) file.readLines() else emptyList()
                val updatedLines = ArrayList<String>()
                val knownAddresses = linkedSetOf<Pair<String, Int>>()
                var found = false

                for (line in lines) {
                    val trimmed = line.trim()

                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        updatedLines += line
                        continue
                    }

                    val parts = trimmed.split(Regex("\\s+"))
                    val existingId = try {
                        PeerId.fromHexString(parts[0])
                    } catch (_: Exception) {
                        updatedLines += line
                        continue
                    }

                    if (existingId != peerId) {
                        updatedLines += line
                        continue
                    }

                    found = true

                    for (entry in parts.drop(1)) {
                        parseAddress(entry)?.let(knownAddresses::add)
                    }
                }

                knownAddresses.addAll(
                    newAddresses.filter { (host, port) ->
                        host.isNotBlank() && port in 1..65535
                    }
                )

                val newLine = buildString {
                    append(peerId)
                    for ((host, port) in knownAddresses) {
                        append(' ')
                        append(formatAddress(host, port))
                    }
                }

                if (found) {
                    val existingIndex = updatedLines.indexOfFirst { line ->
                        val trimmed = line.trim()
                        trimmed.isNotEmpty() &&
                                !trimmed.startsWith("#") &&
                                runCatching {
                                    PeerId.fromHexString(
                                        trimmed.split(Regex("\\s+"))[0]
                                    ) == peerId
                                }.getOrDefault(false)
                    }

                    if (existingIndex >= 0) {
                        updatedLines[existingIndex] = newLine
                    } else {
                        updatedLines += newLine
                    }
                } else {
                    updatedLines += newLine
                }

                file.writeLines(updatedLines)

                logger.info(
                    "Saved peer {} with {} known addresses to {}",
                    peerId,
                    knownAddresses.size,
                    file
                )
            } catch (e: Exception) {
                logger.error("Failed to save known peer {}", peerId, e)
            }
        }
    }

    private fun parseAddress(entry: String): Pair<String, Int>? {
        val normalized = if (entry.startsWith("[")) {
            val end = entry.indexOf(']')
            if (end < 0 || end + 1 >= entry.length ||
                entry[end + 1] != ':'
            ) return null

            entry.substring(1, end) to entry.substring(end + 2).toIntOrNull()
        } else {
            val index = entry.lastIndexOf(':')
            if (index <= 0) return null

            entry.substring(0, index) to entry.substring(index + 1).toIntOrNull()
        }

        val (host, port) = normalized
        if (host.isBlank() || port == null || port !in 1..65535) {
            return null
        }

        return host to port
    }

    private fun formatAddress(host: String, port: Int): String {
        val formattedHost = if (':' in host && !host.startsWith("[")) {
            "[$host]"
        } else {
            host
        }

        return "$formattedHost:$port"
    }

    private fun requireFile(path: Path, description: String): Path {
        if (path.notExists()) throw IllegalArgumentException("$description file not found: $path")
        return path
    }

    private fun loadKnownPeers(file: Path): List<KnownPeer> {
        if (file.notExists()) return emptyList()
        val result = ArrayList<KnownPeer>()
        for (line in file.readLines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            try {
                val parts = trimmed.split(Regex("\\s+"))
                val id = PeerId.fromHexString(parts[0])
                val addresses = parts.drop(1).mapNotNull { entry ->
                    val index = entry.lastIndexOf(':')
                    if (index <= 0) return@mapNotNull null
                    val port = entry.substring(index + 1).toIntOrNull() ?: return@mapNotNull null
                    entry.substring(0, index) to port
                }
                if (addresses.isEmpty()) {
                    logger.warn("Known peer {} has no valid address, skipping", id)
                    continue
                }
                result += KnownPeer(id, addresses)
            } catch (e: Exception) {
                logger.warn("Failed to parse known peer from line: {}", line, e)
            }
        }
        return result
    }

    private suspend fun maintainConnection(peer: KnownPeer) {
        val cfg = config.reconnect
        var failures = 0
        var wait = cfg.initialDelay

        while (currentCoroutineContext().isActive) {
            if (network.peers.containsKey(peer.id)) {
                if (failures > 0) logger.info("Connected to known peer {}", peer.id)
                failures = 0
                wait = cfg.initialDelay
                delay(cfg.checkInterval)
                continue
            }

            if (cfg.maxAttempts != null && failures >= cfg.maxAttempts) {
                logger.warn("Giving up on peer {} after {} failed attempts", peer.id, failures)
                return
            }

            if (tryConnect(peer, cfg)) continue

            failures++
            if (!cfg.enabled) return

            val jittered = wait * (1.0 + Random.nextDouble(-cfg.jitter, cfg.jitter))
            logger.info("Could not connect to {} (attempt {}), retrying in {}", peer.id, failures, jittered)
            delay(jittered)
            wait = minOf(wait * cfg.multiplier, cfg.maxDelay)
        }
    }

    private suspend fun tryConnect(peer: KnownPeer, cfg: NodeConfig.ReconnectConfig): Boolean {
        for ((host, port) in peer.addresses) {
            if (network.peers.containsKey(peer.id)) return true
            logger.info("Connecting to known peer {} at {}:{}", peer.id, host, port)
            try {
                withTimeoutOrNull(cfg.connectTimeout) { network.connectTo(host, port) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ConnectException) {
                logger.debug("Could not connect to {} at {}:{}", peer.id, host, port)
            } catch (e: Exception) {
                logger.debug("Connect to {} at {}:{} failed", peer.id, host, port, e)
            }
            if (network.peers.containsKey(peer.id)) return true
        }
        return false
    }
}