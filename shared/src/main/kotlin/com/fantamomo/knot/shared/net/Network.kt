package com.fantamomo.knot.shared.net

import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.connection.Client
import com.fantamomo.knot.shared.net.connection.Server
import com.fantamomo.knot.shared.net.connection.netty.NettyConnectionProvider
import com.fantamomo.knot.shared.net.mplex.MplexTransport
import com.fantamomo.knot.shared.net.mplex.impl.DefaultMplexTransport
import com.fantamomo.knot.shared.net.packets.Packet
import com.fantamomo.knot.shared.net.packets.PacketEnvelop
import com.fantamomo.knot.shared.net.security.SecureTransport
import com.fantamomo.knot.shared.net.security.impl.PlainSecureTransport
import com.fantamomo.knot.shared.net.transport.Transport
import com.fantamomo.knot.shared.net.util.DefaultPacketDeduplicator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

class Network(
    val scope: CoroutineScope,
    val host: String = "0.0.0.0",
    val port: Int = 5001,
    val localPeerId: PeerId,
//    val localCertificate: X509Certificate?,
//    val localPrivateKey: PrivateKey?,
) {
    private val logger = LoggerFactory.getLogger(Network::class.java)

    private val privateAddress = privateNetworkAddress()

    private val connectMutex = Mutex()

    private val connectedPeers: ConcurrentHashMap<PeerId, ConnectedPeer> = ConcurrentHashMap()

    private val packetDeduplicator = DefaultPacketDeduplicator()
    private val router = Router(this)

    private val incomingPackets = Channel<Packet>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        require(port in 1..65535) { "Port must be between 1 and 65535" }
    }

    val peers: Map<PeerId, ConnectedPeer>
        field: MutableMap<PeerId, ConnectedPeer> = mutableMapOf()

    suspend fun start() {
        val server = NettyConnectionProvider.createServer(host, port)
        server.start()
        scope.launch {
            listen(server)
        }
    }

    suspend fun sendPacket(packet: Packet, target: PeerId) {
        val envelop = PacketEnvelop(packet = packet, source = localPeerId, target = target)
        packetDeduplicator.isDuplicateAndMark(envelop)
        val transport = peers[target] ?: throw IllegalStateException("No peer found for $target")
        transport.send(envelop)
    }

    suspend fun listen(server: Server) = coroutineScope {
        while (isActive) {
            val client = server.accept()
            scope.launch {
                handleClient(client, false)
            }
        }
    }

    private suspend fun createSecureTransport(transport: Transport, initiator: Boolean): SecureTransport {
        val secureTransport = PlainSecureTransport(transport, localPeerId)
        secureTransport.start()
        if (initiator) {
            secureTransport.doClientHandshake()
        } else {
            secureTransport.doServerHandshake()
        }
        return secureTransport
    }

    private suspend fun createMplexTransport(transport: SecureTransport, initiator: Boolean): MplexTransport {
        val mplexTransport = DefaultMplexTransport(transport)
        mplexTransport.start()
        scope.launch(Dispatchers.Unconfined) {
            mplexTransport.run()
        }
        return mplexTransport
    }

    private suspend fun handleClient(client: Client, initiator: Boolean) {
        val transport = client.transport
        transport.start()
        val secureTransport = createSecureTransport(transport, initiator)
        val mplexTransport = createMplexTransport(secureTransport, initiator)
        val remotePeerId = secureTransport.remotePeerId
        val connectedPeer = ConnectedPeer(this, remotePeerId, mplexTransport, client)
        connectedPeer.start()
        connectedPeers[remotePeerId] = connectedPeer

    }

    suspend fun connectTo(host: String, port: Int) {
        val client = NettyConnectionProvider.createClient(host, port)
        handleClient(client, true)
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

        // if this is true: we do NOT drop it, but we do not forward it either (because all receivers would drop it)
        val canForward = envelop.ttl == 1.toByte()

        // first some simple checks
        if (envelop.source == localPeerId) {
            // interesting, did you send it to yourself?
            return
        }

        if (envelop.trace.contains(localPeerId)) {
            // still interesting that we even get here because it should have been dropped by the DEDUPLICATOR
            return
        }

        if (!envelop.trace.contains(peer.peerId)) {
            // peer sent us a packet but didn't add itself to the trace
            logger.warn("Received packet from $peer with trace [${envelop.trace.joinToString(" -> ")}] but peer is not in trace")
            return
        }

        if (envelop.trace.contains(envelop.target)) {
            // target received the packet but still forwarded it???
            logger.warn("Received packet from $peer with trace [${envelop.trace.joinToString(" -> ")}] but target is already in trace")
            return
        }

        if (envelop.target == localPeerId) {
            // simple, just send it to the incoming packet channel
            incomingPackets.send(envelop.packet)
        } else if (envelop.target.isBroadcast) {
            // broadcast, send it to the incoming packet channel
            incomingPackets.send(envelop.packet)
            if (canForward) {
                // and if the ttl is higher than 1 send it to the eligible targets (which are all peers that are not in the trace)
                val eligibleTargets = peers.values.filter { !envelop.trace.contains(it.peerId) }
                val toSend = envelop.withNextHop(localPeerId)
                for (target in eligibleTargets) {
                    target.send(toSend)
                }
            }
        } else {
            // we received a packet for a peer that is not us and not a broadcast,
            // so we delegate it to the router which will try to find a path to the target
            val toSend = envelop.withNextHop(localPeerId)
            router.route(toSend)
        }
    }

    fun incomingPackets(): Flow<Packet> = incomingPackets.receiveAsFlow()

    inline fun <reified T : Packet> incomingPackets(): Flow<T> = incomingPackets().filterIsInstance<T>()

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