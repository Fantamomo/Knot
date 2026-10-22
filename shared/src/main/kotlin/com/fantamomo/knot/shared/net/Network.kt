package com.fantamomo.knot.shared.net

import com.fantamomo.knot.shared.model.PeerAddress
import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.connection.Client
import com.fantamomo.knot.shared.net.connection.Server
import com.fantamomo.knot.shared.net.connection.netty.NettyConnectionProvider
import com.fantamomo.knot.shared.net.mplex.MplexTransport
import com.fantamomo.knot.shared.net.mplex.impl.DirectMplexTransport
import com.fantamomo.knot.shared.net.packets.IncomingPacket
import com.fantamomo.knot.shared.net.packets.Packet
import com.fantamomo.knot.shared.net.packets.PacketEnvelop
import com.fantamomo.knot.shared.net.packets.internal.ManagementPacket
import com.fantamomo.knot.shared.net.security.SecureTransport
import com.fantamomo.knot.shared.net.security.impl.TlsSecureTransport
import com.fantamomo.knot.shared.net.transport.Transport
import com.fantamomo.knot.shared.net.util.DefaultPacketDeduplicator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.inputStream

class Network(
    val scope: CoroutineScope,
    val host: String? = "0.0.0.0",
    val port: Int? = 5001,
    val localPeerId: PeerId,
    keyStoreFile: Path,
    keyStorePassword: CharArray,
    caCertificateInput: Path,
    private val onPeerConnected: (PeerId) -> Unit = {}
) {
    private val logger = LoggerFactory.getLogger(Network::class.java)

    private val privateAddress = privateNetworkAddress()

    private val packetDeduplicator = DefaultPacketDeduplicator()
    val router = Router(this)

    private val incomingPackets = MutableSharedFlow<IncomingPacket<Packet>>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    private val sslContext = TlsSecureTransport.createMutualTlsContext(
        keyStoreFile.inputStream(),
        keyStorePassword,
        caCertificateInput.inputStream()
    )

    init {
        require(port == null || port in 1..65535) { "Port must be between 1 and 65535" }
        keyStorePassword.fill('0')
    }

    val peers: Map<PeerId, ConnectedPeer>
        field: ConcurrentHashMap<PeerId, ConnectedPeer> = ConcurrentHashMap()

    suspend fun start() {
        val server = if (host != null && port != null) NettyConnectionProvider.createServer(host, port) else null
        server?.start()
        scope.launch {
            router.start()
        }
        if (server != null) {
            scope.launch {
                listen(server)
            }
        }
    }

    suspend fun sendPacket(packet: Packet, target: PeerId) {
        val envelop = PacketEnvelop(packet = packet, source = localPeerId, target = target)
        packetDeduplicator.isDuplicateAndMark(envelop)
        val peer = peers[target]
        if (peer != null) {
            peer.send(envelop)
        } else {
            router.route(envelop)
        }
    }

    suspend fun listen(server: Server) = coroutineScope {
        while (isActive) {
            val client = server.accept()
            scope.launch {
                handleClient(client, false)
            }
        }
    }

    suspend fun connectTo(host: String, port: Int) {
        logger.debug("Connecting to $host:$port")
        val client = NettyConnectionProvider.createClient(host, port)
        handleClient(client, true)
    }

    internal fun localAddresses(): List<PeerAddress>? {
        if (host == null || port == null) return null
        val advertised = if (host == "0.0.0.0") privateAddress.hostAddress else host
        return listOf(PeerAddress(advertised, port))
    }

    private suspend fun createMplexTransport(secureTransport: SecureTransport, initiator: Boolean): MplexTransport {
        //return DefaultMplexTransport(secureTransport, if (initiator) 0 else 1)
        return DirectMplexTransport(secureTransport)
    }

    private suspend fun createSecureTransport(transport: Transport, initiator: Boolean): SecureTransport {
        val secureTransport = TlsSecureTransport(transport, localPeerId, sslContext)
        secureTransport.start()
        if (initiator) {
            secureTransport.doClientHandshake()
        } else {
            secureTransport.doServerHandshake()
        }
        return secureTransport
    }

    private suspend fun handleClient(client: Client, initiator: Boolean) {
        logger.debug("Handling client connection from {}", client.transport.remoteAddr)
        try {
            val transport = client.transport
            transport.start()
            val secureTransport = createSecureTransport(transport, initiator)
            val remotePeerId = secureTransport.remotePeerId

            val mplexTransport = createMplexTransport(secureTransport, initiator)
            mplexTransport.start()

            val connectedPeer = ConnectedPeer(this, remotePeerId, mplexTransport, client)

            if (remotePeerId == localPeerId || peers.putIfAbsent(remotePeerId, connectedPeer) != null) {
                secureTransport.close(IllegalStateException("Duplicate or self connection to $remotePeerId"))
                return
            }

            connectedPeer.start()

            scope.launch(Dispatchers.Unconfined) {
                try {
                    mplexTransport.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("Connection to {} terminated", remotePeerId, e)
                } finally {
                    if (peers.remove(remotePeerId, connectedPeer)) {
                        router.onPeerDisconnected(remotePeerId)
                    }
                }
            }

            logger.debug("Connection to {} established", remotePeerId)
            router.onPeerConnected(remotePeerId)
            try {
                onPeerConnected(remotePeerId)
            } catch (e: Exception) {
                logger.warn("onPeerConnected failed for {}", remotePeerId, e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to establish connection", e)
        }
    }

    internal suspend fun handlePacket(peer: ConnectedPeer, envelop: PacketEnvelop) {
        if (packetDeduplicator.isDuplicateAndMark(envelop)) return

        if (envelop.ttl <= 0) {
            logger.debug(
                "Dropped {} ({} -> {}; type={}) due to TTL",
                envelop.id,
                envelop.source,
                envelop.target,
                envelop.codec.id
            )
            return
        }

        if (envelop.source == localPeerId) return

        if (envelop.trace.contains(localPeerId)) return

        if (!envelop.trace.contains(peer.peerId)) {
            logger.warn("Received packet from $peer with trace [${envelop.trace.joinToString(" -> ")}] but peer is not in trace")
            return
        }

        if (envelop.trace.contains(envelop.target)) {
            logger.warn("Received packet from $peer with trace [${envelop.trace.joinToString(" -> ")}] but target is already in trace")
            return
        }

        val target = envelop.target

        if (target == localPeerId) {
            deliver(envelop)
            return
        }

        val forwarded = if (envelop.ttl > 1) envelop.withNextHop(localPeerId) else null

        if (target.isBroadcast) {
            if (forwarded != null) router.route(forwarded)
            deliver(envelop)
        } else if (forwarded != null) {
            router.route(forwarded)
        }
    }

    private suspend fun deliver(envelop: PacketEnvelop) {
        val packet = try {
            envelop.packet
        } catch (e: Exception) {
            logger.warn("Failed to decode packet {} from {}", envelop.id, envelop.source, e)
            return
        }
        val incoming = IncomingPacket(packet, envelop)
        if (packet is ManagementPacket) {
            router.handleManagement(incoming)
        } else {
            incomingPackets.emit(incoming)
        }
    }

    fun incomingPackets(): Flow<IncomingPacket<*>> = incomingPackets.asSharedFlow()

    companion object {
        private fun privateNetworkAddress(): InetAddress {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val addresses = interfaces.flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .sortedBy { it.hostAddress }
            return if (addresses.isNotEmpty()) {
                addresses[0]
            } else {
                InetAddress.getLoopbackAddress()
            }
        }
    }
}