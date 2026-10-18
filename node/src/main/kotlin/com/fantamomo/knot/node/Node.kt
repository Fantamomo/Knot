package com.fantamomo.knot.node

import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.Network
import com.fantamomo.knot.shared.utils.switch
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.random.Random

object Node {
    private val logger = LoggerFactory.getLogger(Node::class.java)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val started = atomic(false)

    lateinit var config: NodeConfig
        private set

    val configDir: Path get() = config.configDir

    lateinit var network: Network
        private set

    private class KnownPeer(val id: PeerId, val addresses: List<Pair<String, Int>>)

    suspend fun start(args: Array<String>) {
        if (!started.compareAndSet(expect = false, update = true)) throw IllegalStateException("Node is already running")

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

        awaitCancellation()
    }

    private fun onPeerConnected(peerId: PeerId) {
        logger.info("Peer connected: {}", peerId)

        scope.launch {
            val file = config.configDir.resolve(config.knownPeersFileName)

            val peer = network.peers[peerId] ?: return@launch

            synchronized(this@Node) {
                val lines = if (file.notExists()) {
                    emptyList()
                } else {
                    file.readLines()
                }

                val alreadyKnown = lines.any { line ->
                    val trimmed = line.trim()
                    trimmed.isNotEmpty() &&
                            !trimmed.startsWith("#") &&
                            trimmed.split(Regex("\\s+")).firstOrNull() == peerId.toString()
                }

                if (alreadyKnown) return@launch

                file.parent?.takeUnless(Path::exists)?.createDirectories()

                val updated = lines + "$peerId ${peer.client.transport.remoteAddr}:${peer.client.transport.remotePort}"

                file.writeLines(updated)

                logger.info("Added peer {} to known peers file {}", peerId, file)
            }
        }
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
            } catch (e: Exception) {
                logger.debug("Connect to {} at {}:{} failed", peer.id, host, port, e)
            }
            if (network.peers.containsKey(peer.id)) return true
        }
        return false
    }
}