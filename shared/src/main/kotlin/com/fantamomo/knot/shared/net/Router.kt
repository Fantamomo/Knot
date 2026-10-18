package com.fantamomo.knot.shared.net

import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.packets.IncomingPacket
import com.fantamomo.knot.shared.net.packets.Packet
import com.fantamomo.knot.shared.net.packets.PacketEnvelop
import com.fantamomo.knot.shared.net.packets.internal.*
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.updateAndGet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.LongAdder
import kotlin.time.Duration.Companion.milliseconds

class Router(val network: Network) {
    private val logger = LoggerFactory.getLogger(Router::class.java)

    private class Node(val advertisement: PeerAdvertisement) {
        val neighbors: Set<PeerId> = advertisement.connectedPeers.toHashSet()

        @Volatile
        var lastSeen: Long = System.currentTimeMillis()
    }

    private class RouteTable(val version: Long, val nextHops: Map<PeerId, PeerId>)

    private class Pending(val envelop: PacketEnvelop, val deadline: Long)

    private data class FlowKey(val source: PeerId, val target: PeerId)

    private val nodes = ConcurrentHashMap<PeerId, Node>()
    private val topologyVersion = atomic(0L)

    @Volatile
    private var table = RouteTable(-1, emptyMap())
    private val tableLock = Any()

    private val sequence = atomic(0L)
    private val advertiseTrigger = Channel<Unit>(Channel.CONFLATED)

    private val pending = ConcurrentHashMap<PeerId, ConcurrentLinkedQueue<Pending>>()
    private val pendingCount = atomic(0)
    private val lastDiscovery = atomic(0L)
    private val lastSyncServed = ConcurrentHashMap<PeerId, Long>()

    private val flows = ConcurrentHashMap<FlowKey, LongAdder>()
    private val hintCooldown = ConcurrentHashMap<FlowKey, Long>()
    private val connecting = ConcurrentHashMap.newKeySet<PeerId>()

    private val latencies = ConcurrentHashMap<PeerId, Long>()

    suspend fun start() = coroutineScope {
        launch { advertiseLoop() }
        launch { pendingLoop() }
        launch { maintenanceLoop() }
        launch { gossipLoop() }
        launch { trafficLoop() }
    }

    fun knownPeers(): Set<PeerId> = nodes.keys.toSet()

    fun knownAddresses(peerId: PeerId): List<Pair<String, Int>> {
        return nodes[peerId]
            ?.advertisement
            ?.knownAddresses
            .orEmpty()
            .mapNotNull { address ->
                val port = address.port ?: return@mapNotNull null

                if (address.address.isBlank() || port !in 1..65535) {
                    return@mapNotNull null
                }

                address.address to port
            }
    }

    fun latency(peer: PeerId): Long? = latencies[peer]

    fun onPeerConnected(peerId: PeerId) {
        topologyVersion.incrementAndGet()
        advertiseTrigger.trySend(Unit)
        network.scope.launch {
            guarded { sendAdvertisements(allAdvertisements(), peerId) }
            flushPending()
        }
    }

    fun onPeerDisconnected(peerId: PeerId) {
        latencies.remove(peerId)
        topologyVersion.incrementAndGet()
        advertiseTrigger.trySend(Unit)
    }

    suspend fun route(envelop: PacketEnvelop) {
        if (envelop.ttl <= 0) return
        val target = envelop.target

        if (target.isBroadcast) {
            flood(envelop)
            return
        }

        val direct = network.peers[target]
        if (direct != null) {
            account(envelop)
            if (!forward(direct, envelop)) fallback(envelop)
            return
        }

        val hop = nextHopAvoiding(target, envelop.trace)
        val peer = hop?.let { network.peers[it] }
        if (peer != null) {
            account(envelop)
            if (!forward(peer, envelop)) fallback(envelop)
            return
        }

        if (envelop.source == network.localPeerId) {
            enqueue(envelop)
        } else {
            discover()
            flood(envelop)
        }
    }

    suspend fun handleManagement(incoming: IncomingPacket<Packet>) {
        val source = incoming.source
        val packet = incoming.packet as? ManagementPacket ?: return
        try {
            when (packet) {
                is PeerAdvertisement -> {
                    if (packet.peerId == source && apply(packet, true)) flushPending()
                }

                is TopologySyncPacket -> {
                    var changed = false
                    for (advertisement in packet.advertisements) {
                        if (apply(advertisement, false)) changed = true
                    }
                    if (changed) flushPending()
                }

                is KnownPeersPacket -> handleDigest(packet, source)
                is DirectConnectHint -> {
                    network.scope.launch { handleHint(packet) }
                }

                is PingPacket -> {
                    network.sendPacket(PongPacket(System.currentTimeMillis(), packet.timestamp), source)
                }

                is PongPacket -> {
                    latencies[source] = System.currentTimeMillis() - packet.pingTimestamp
                }

                RequestKnownPeersPacket -> serveTopology(source)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to handle management packet from {}", source, e)
        }
    }

    private suspend inline fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Router task failed", e)
        }
    }

    private fun nextSequence(): Long = sequence.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }

    private fun selfAdvertisement() = network.localAddresses()?.let {
        PeerAdvertisement(
            peerId = network.localPeerId,
            knownAddresses = it,
            connectedPeers = network.peers.keys.toList(),
            sequence = nextSequence()
        )
    }

    private fun allAdvertisements(): List<PeerAdvertisement> {
        val result = ArrayList<PeerAdvertisement>(nodes.size + 1)
        val selfAdvertisement = selfAdvertisement()
        if (selfAdvertisement != null) result += selfAdvertisement
        for (node in nodes.values) result += node.advertisement
        return result
    }

    private suspend fun sendAdvertisements(advertisements: List<PeerAdvertisement>, to: PeerId) {
        for (chunk in advertisements.chunked(SYNC_CHUNK)) {
            network.sendPacket(TopologySyncPacket(chunk), to)
        }
    }

    private suspend fun serveTopology(to: PeerId) {
        val now = System.currentTimeMillis()
        val last = lastSyncServed.put(to, now)
        if (last != null && now - last < SYNC_MIN_INTERVAL_MS) return
        sendAdvertisements(allAdvertisements(), to)
    }

    private suspend fun handleDigest(packet: KnownPeersPacket, source: PeerId) {
        val local = network.localPeerId
        val theirs = packet.knownPeers.toHashSet()
        if (theirs.any { it != local && !nodes.containsKey(it) }) {
            network.sendPacket(RequestKnownPeersPacket, source)
        }
        if (packet.knownPeers.size < MAX_DIGEST) {
            val missing = nodes.values
                .filter { it.advertisement.peerId !in theirs && it.advertisement.peerId != source }
                .map { it.advertisement }
            if (missing.isNotEmpty()) sendAdvertisements(missing, source)
        }
    }

    private fun apply(advertisement: PeerAdvertisement, fresh: Boolean): Boolean {
        if (advertisement.peerId == network.localPeerId) return false
        if (advertisement.connectedPeers.size > MAX_NEIGHBORS) return false
        if (advertisement.knownAddresses.size > MAX_ADDRESSES) return false
        if (!fresh && advertisement.sequence < System.currentTimeMillis() - NODE_TTL_MS) return false
        if (nodes.size >= MAX_NODES && !nodes.containsKey(advertisement.peerId)) return false

        var changed = false
        nodes.compute(advertisement.peerId) { _, old ->
            if (old == null) {
                changed = true
                Node(advertisement)
            } else if (old.advertisement.sequence >= advertisement.sequence) {
                old
            } else {
                Node(advertisement).also { changed = it.neighbors != old.neighbors }
            }
        }
        if (changed) topologyVersion.incrementAndGet()
        return changed
    }

    private fun nextHop(target: PeerId): PeerId? {
        var current = table
        if (current.version != topologyVersion.value) current = rebuild()
        return current.nextHops[target]
    }

    private fun rebuild(): RouteTable = synchronized(tableLock) {
        val version = topologyVersion.value
        val existing = table
        if (existing.version == version) {
            existing
        } else {
            RouteTable(version, computeRoutes(emptyList(), null)).also { table = it }
        }
    }

    private fun nextHopAvoiding(target: PeerId, trace: List<PeerId>): PeerId? {
        val hop = nextHop(target) ?: return null
        if (hop !in trace) return hop
        return computeRoutes(trace, target)[target]
    }

    private fun computeRoutes(excluded: List<PeerId>, goal: PeerId?): Map<PeerId, PeerId> {
        val local = network.localPeerId
        val firstHop = HashMap<PeerId, PeerId>()
        val queue = ArrayDeque<PeerId>()

        for (neighbor in network.peers.keys) {
            if (neighbor in excluded) continue
            firstHop[neighbor] = neighbor
            queue.addLast(neighbor)
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == goal) break
            val node = nodes[current] ?: continue
            val hop = firstHop.getValue(current)
            for (next in node.neighbors) {
                if (next == local || next in firstHop || next in excluded) continue
                val other = nodes[next]
                if (other != null && current !in other.neighbors) continue
                firstHop[next] = hop
                queue.addLast(next)
            }
        }
        return firstHop
    }

    private suspend fun forward(peer: ConnectedPeer, envelop: PacketEnvelop): Boolean = try {
        peer.send(envelop)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug("Failed to forward {} via {}", envelop.id, peer.peerId, e)
        false
    }

    private suspend fun flood(envelop: PacketEnvelop) {
        val targets = network.peers.values.filter { it.peerId !in envelop.trace }
        when (targets.size) {
            0 -> return
            1 -> forward(targets[0], envelop)
            else -> coroutineScope {
                for (target in targets) launch { forward(target, envelop) }
            }
        }
    }

    private suspend fun fallback(envelop: PacketEnvelop) {
        if (envelop.source == network.localPeerId) enqueue(envelop) else flood(envelop)
    }

    private suspend fun enqueue(envelop: PacketEnvelop) {
        if (pendingCount.value >= MAX_PENDING) {
            flood(envelop)
            return
        }
        val entry = Pending(envelop, System.currentTimeMillis() + PENDING_TIMEOUT_MS)
        pendingCount.incrementAndGet()
        pending.compute(envelop.target) { _, queue ->
            (queue ?: ConcurrentLinkedQueue()).also { it.add(entry) }
        }
        discover()
    }

    private suspend fun discover() {
        val now = System.currentTimeMillis()
        val last = lastDiscovery.value
        if (now - last < DISCOVERY_INTERVAL_MS || !lastDiscovery.compareAndSet(last, now)) return
        for (peer in network.peers.keys) {
            guarded { network.sendPacket(RequestKnownPeersPacket, peer) }
        }
    }

    private suspend fun flushPending() {
        if (pendingCount.value == 0) return
        for (target in pending.keys.toList()) {
            if (network.peers[target] == null && nextHop(target) == null) continue
            val queue = pending[target] ?: continue
            val batch = ArrayList<PacketEnvelop>()
            while (true) {
                val entry = queue.poll() ?: break
                pendingCount.decrementAndGet()
                batch += entry.envelop
            }
            pending.computeIfPresent(target) { _, q -> if (q.isEmpty()) null else q }
            for (envelop in batch) route(envelop)
        }
    }

    private fun account(envelop: PacketEnvelop) {
        val local = network.localPeerId
        if (envelop.source == local || envelop.target == local) return
        val key = if (compare(envelop.source, envelop.target) <= 0) {
            FlowKey(envelop.source, envelop.target)
        } else {
            FlowKey(envelop.target, envelop.source)
        }
        flows.computeIfAbsent(key) { LongAdder() }.increment()
    }

    private fun compare(a: PeerId, b: PeerId): Int {
        val x = a.id
        val y = b.id
        for (i in 0 until minOf(x.size, y.size)) {
            val diff = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return x.size - y.size
    }

    private suspend fun suggestDirectLink(key: FlowKey) {
        val now = System.currentTimeMillis()
        val last = hintCooldown[key]
        if (last != null && now - last < HINT_COOLDOWN_MS) return
        val responder = nodes[key.target] ?: return
        if (nodes[key.source] == null) return
        val addresses = responder.advertisement.knownAddresses
        if (addresses.isEmpty()) return
        hintCooldown[key] = now
        network.sendPacket(DirectConnectHint(key.target, addresses), key.source)
    }

    private suspend fun handleHint(hint: DirectConnectHint) {
        val target = hint.peerId
        if (target == network.localPeerId || network.peers.containsKey(target)) return
        if (network.peers.size >= MAX_DIRECT_PEERS) return
        if (!connecting.add(target)) return
        try {
            for (address in hint.addresses) {
                try {
                    network.connectTo(address.address, address.port ?: /* TODO */80)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("Direct connect to {} via {} failed", target, address, e)
                }
            }
        } finally {
            connecting.remove(target)
        }
    }

    private suspend fun advertiseLoop() {
        for (signal in advertiseTrigger) {
            val selfAdvertisement = selfAdvertisement()
            if (selfAdvertisement != null) guarded { network.sendPacket(selfAdvertisement, PeerId.broadcast()) }
            delay(ADVERTISE_COALESCE_MS.milliseconds)
        }
    }

    private suspend fun pendingLoop() {
        while (true) {
            delay(PENDING_SWEEP_MS.milliseconds)
            if (pendingCount.value == 0) continue
            val now = System.currentTimeMillis()
            val expired = ArrayList<PacketEnvelop>()
            for (target in pending.keys.toList()) {
                pending.computeIfPresent(target) { _, queue ->
                    val iterator = queue.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.deadline <= now) {
                            iterator.remove()
                            pendingCount.decrementAndGet()
                            expired += entry.envelop
                        }
                    }
                    if (queue.isEmpty()) null else queue
                }
            }
            for (envelop in expired) guarded { flood(envelop) }
        }
    }

    private suspend fun maintenanceLoop() {
        while (true) {
            delay(REFRESH_INTERVAL_MS.milliseconds)
            advertiseTrigger.trySend(Unit)
            val now = System.currentTimeMillis()
            if (nodes.values.removeIf { it.lastSeen < now - NODE_TTL_MS }) {
                topologyVersion.incrementAndGet()
            }
            lastSyncServed.values.removeIf { now - it > REFRESH_INTERVAL_MS }
            hintCooldown.values.removeIf { now - it > HINT_COOLDOWN_MS }
        }
    }

    private suspend fun gossipLoop() {
        while (true) {
            delay(GOSSIP_INTERVAL_MS.milliseconds)
            val peer = network.peers.keys.randomOrNull() ?: continue
            guarded { network.sendPacket(KnownPeersPacket(nodes.keys.take(MAX_DIGEST)), peer) }
        }
    }

    private suspend fun trafficLoop() {
        while (true) {
            delay(FLOW_WINDOW_MS.milliseconds)
            if (flows.isEmpty()) continue
            val hot = flows.entries.filter { it.value.sum() >= FLOW_THRESHOLD }.map { it.key }
            flows.clear()
            for (key in hot) guarded { suggestDirectLink(key) }
        }
    }

    companion object {
        private const val SYNC_CHUNK = 16
        private const val SYNC_MIN_INTERVAL_MS = 1_000L
        private const val MAX_DIGEST = 3_000
        private const val MAX_NEIGHBORS = 128
        private const val MAX_ADDRESSES = 8
        private const val MAX_NODES = 10_000
        private const val MAX_PENDING = 2_048
        private const val MAX_DIRECT_PEERS = 32

        private const val ADVERTISE_COALESCE_MS = 50L
        private const val DISCOVERY_INTERVAL_MS = 500L
        private const val PENDING_TIMEOUT_MS = 3_000L
        private const val PENDING_SWEEP_MS = 250L
        private const val REFRESH_INTERVAL_MS = 60_000L
        private const val NODE_TTL_MS = 180_000L
        private const val GOSSIP_INTERVAL_MS = 30_000L

        private const val FLOW_WINDOW_MS = 5_000L
        private const val FLOW_THRESHOLD = 500L
        private const val HINT_COOLDOWN_MS = 60_000L
    }
}