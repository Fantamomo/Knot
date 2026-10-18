package com.fantamomo.knot.shared.manager

import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.Network
import com.fantamomo.knot.shared.net.packets.tunnel.*
import com.fantamomo.knot.shared.net.util.incomingPackets
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class TunnelManager(
    val network: Network,
    private val allowBind: (source: PeerId, host: String, port: Int) -> Boolean = { _, _, _ -> true },
) {
    private class PendingOpen(
        val destinationHost: String,
        val destinationPort: Int,
        val deferred: CompletableDeferred<OpenTunnelResponsePacket> = CompletableDeferred(),
    )

    private val pendingOpens = ConcurrentHashMap<Uuid, PendingOpen>()

    private val clientTunnels = ConcurrentHashMap<Uuid, Tunnel>()

    private val nodeTunnels = ConcurrentHashMap<Uuid, NodeTunnel>()

    private val streams = ConcurrentHashMap<Uuid, TunnelStream>()

    suspend fun start() {
        network.incomingPackets<TunnelPacket>().collect { incomingPacket ->
            val source = incomingPacket.source
            when (val packet = incomingPacket.packet) {
                is OpenTunnelRequestPacket -> handleOpenTunnelRequest(packet, source)
                is OpenTunnelResponsePacket -> handleOpenTunnelResponse(packet, source)
                is IncomingTunnelStreamPacket -> handleIncomingStream(packet, source)
                is IncomingTunnelStreamDataPacket -> handleData(packet.sessionId, packet.data, source)
                is OutgoingTunnelStreamDataPacket -> handleData(packet.sessionId, packet.data, source)
                is CloseStreamPacket -> handleCloseStream(packet, source)
            }
        }
    }

    suspend fun openTunnel(
        target: PeerId,
        destinationHost: String,
        destinationPort: Int,
        bindHost: String = "0.0.0.0",
        bindPort: Int = 0,
    ): Tunnel {
        val request = OpenTunnelRequestPacket(host = bindHost, port = bindPort)
        val pending = PendingOpen(destinationHost, destinationPort)
        if (pendingOpens.putIfAbsent(request.requestId, pending) != null) {
            throw IllegalStateException("Request ID already in use")
        }

        val response = try {
            network.sendPacket(request, target)
            withTimeoutOrNull(5.seconds) { pending.deferred.await() }
        } finally {
            pendingOpens.remove(request.requestId)
        }

        if (response == null) throw IOException("Peer did not respond to open tunnel request")
        if (!response.success) throw IOException("Peer rejected tunnel request, reason: ${response.reason}")
        val tunnelId = response.tunnelId ?: throw IllegalStateException("Tunnel ID not provided") // unreachable

        return clientTunnels[tunnelId] ?: throw IOException("Tunnel was closed before it could be used")
    }

    private fun handleOpenTunnelResponse(packet: OpenTunnelResponsePacket, source: PeerId) {
        val pending = pendingOpens[packet.requestId] ?: return
        if (packet.success && packet.tunnelId != null) {
            clientTunnels[packet.tunnelId] = Tunnel(
                id = packet.tunnelId,
                node = source,
                destinationHost = pending.destinationHost,
                destinationPort = pending.destinationPort,
                boundHost = packet.host,
                boundPort = packet.port,
            )
        }
        pending.deferred.complete(packet)
    }

    inner class Tunnel internal constructor(
        val id: Uuid,
        val node: PeerId,
        val destinationHost: String,
        val destinationPort: Int,
        val boundHost: String,
        val boundPort: Int,
    ) {
        fun close() {
            if (clientTunnels.remove(id) == null) return
            streams.values.filter { it.tunnelId == id }.forEach { it.close(notifyPeer = true) }
        }
    }

    private class NodeTunnel(val id: Uuid, val owner: PeerId, val server: ServerSocket) {
        var acceptJob: Job? = null
    }

    private fun handleOpenTunnelRequest(packet: OpenTunnelRequestPacket, source: PeerId) {
        network.scope.launch {
            suspend fun reject(reason: String) {
                sendQuietly(
                    OpenTunnelResponsePacket(packet.requestId, false, reason, null, packet.host, packet.port),
                    source,
                )
            }

            if (!allowBind(source, packet.host, packet.port)) {
                reject("Not allowed")
                return@launch
            }

            val server = try {
                withContext(Dispatchers.IO) {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(packet.host, packet.port), 50)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.warn("Failed to open tunnel server on ${packet.host}:${packet.port}", e)
                reject(e.message ?: "Failed to bind")
                return@launch
            }

            val tunnel = NodeTunnel(Uuid.random(), source, server)
            nodeTunnels[tunnel.id] = tunnel
            try {
                network.sendPacket(
                    OpenTunnelResponsePacket(
                        packet.requestId, true, null, tunnel.id,
                        server.inetAddress.hostAddress, server.localPort,
                    ),
                    source,
                )
            } catch (e: Exception) {
                nodeTunnels.remove(tunnel.id)
                runCatching { server.close() }
                throw e
            }
            tunnel.acceptJob = launch(Dispatchers.IO) { acceptLoop(tunnel) }
        }
    }

    private suspend fun acceptLoop(tunnel: NodeTunnel) {
        while (!tunnel.server.isClosed) {
            val socket = try {
                @Suppress("BlockingMethodInNonBlockingContext")
                tunnel.server.accept()
            } catch (_: IOException) {
                break
            }

            val stream = TunnelStream(Uuid.random(), tunnel.owner, tunnel.id)
            stream.socket = socket
            streams[stream.id] = stream

            val sourceAddress = socket.remoteSocketAddress.toString()
            stream.start(
                send = { data -> network.sendPacket(IncomingTunnelStreamDataPacket(stream.id, data), tunnel.owner) },
                open = {
                    network.sendPacket(
                        IncomingTunnelStreamPacket(tunnel.id, stream.id, sourceAddress, ByteArray(0)),
                        tunnel.owner,
                    )
                    socket
                },
            )
        }
    }

    private fun handleIncomingStream(packet: IncomingTunnelStreamPacket, source: PeerId) {
        val tunnel = clientTunnels[packet.tunnelId]
        if (tunnel == null || tunnel.node != source) {
            logger.warn("Received stream for unknown tunnel ${packet.tunnelId} from $source")
            network.scope.launch { sendQuietly(CloseStreamPacket(packet.sessionId), source) }
            return
        }

        val stream = TunnelStream(packet.sessionId, source, packet.tunnelId)
        if (streams.putIfAbsent(stream.id, stream) != null) {
            logger.warn("Received duplicate session id ${stream.id} from $source")
            return
        }
        if (packet.data.isNotEmpty()) stream.writeQueue.trySend(packet.data)

        stream.start(
            send = { data -> network.sendPacket(OutgoingTunnelStreamDataPacket(stream.id, data), source) },
            open = {
                val socket = Socket()
                stream.socket = socket
                socket.connect(InetSocketAddress(tunnel.destinationHost, tunnel.destinationPort), CONNECT_TIMEOUT_MS)
                socket
            },
        )
    }

    private fun handleData(sessionId: Uuid, data: ByteArray, source: PeerId) {
        val stream = streams[sessionId]
        if (stream == null) {
            logger.debug("Received data for unknown session {}", sessionId)
            return
        }
        if (stream.peer != source) {
            logger.warn("Received stream data from wrong source $source for session $sessionId")
            return
        }
        stream.writeQueue.trySend(data)
    }

    private fun handleCloseStream(packet: CloseStreamPacket, source: PeerId) {
        val stream = streams[packet.sessionId] ?: return
        if (stream.peer != source) {
            logger.warn("Received close stream from wrong source $source for session ${packet.sessionId}")
            return
        }
        stream.onPeerClosed()
    }

    fun stop() {
        clientTunnels.values.toList().forEach { it.close() }
        nodeTunnels.values.toList().forEach { tunnel ->
            runCatching { tunnel.server.close() }
            tunnel.acceptJob?.cancel()
        }
        nodeTunnels.clear()
        streams.values.toList().forEach { it.close(notifyPeer = true) }
        pendingOpens.values.forEach { it.deferred.cancel() }
        pendingOpens.clear()
    }

    private suspend fun sendQuietly(packet: TunnelPacket, target: PeerId) {
        try {
            network.sendPacket(packet, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Failed to send {} to {}", packet, target, e)
        }
    }

    private inner class TunnelStream(val id: Uuid, val peer: PeerId, val tunnelId: Uuid) {
        val writeQueue = Channel<ByteArray>(Channel.UNLIMITED)

        @Volatile
        var socket: Socket? = null

        @Volatile
        private var job: Job? = null

        private val closed = AtomicBoolean(false)

        fun start(send: suspend (ByteArray) -> Unit, open: suspend () -> Socket) {
            job = network.scope.launch(Dispatchers.IO) {
                try {
                    val s = open()
                    socket = s
                    if (closed.get()) {
                        runCatching { s.close() }
                        return@launch
                    }

                    launch(Dispatchers.IO) { writeLoop(s) }

                    val input = s.getInputStream()
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        send(buffer.copyOf(read))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!closed.get()) logger.debug("Tunnel stream {} failed", id, e)
                } finally {
                    close(notifyPeer = true)
                }
            }
        }

        @Suppress("BlockingMethodInNonBlockingContext") // already done in launch
        private suspend fun writeLoop(s: Socket) {
            try {
                val out = s.getOutputStream()
                for (data in writeQueue) {
                    out.write(data)
                    out.flush()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!closed.get()) logger.debug("Tunnel stream {} write failed", id, e)
            } finally {
                close(notifyPeer = false)
            }
        }

        fun onPeerClosed() {
            writeQueue.close()
            if (socket?.isConnected != true) close(notifyPeer = false)
        }

        fun close(notifyPeer: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            streams.remove(id)
            writeQueue.close()
            runCatching { socket?.close() }
            if (notifyPeer) {
                network.scope.launch { sendQuietly(CloseStreamPacket(id), peer) }
            }
            job?.cancel()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TunnelManager::class.java)

        private const val CHUNK_SIZE = 4096
        private const val CONNECT_TIMEOUT_MS = 10_000
    }
}