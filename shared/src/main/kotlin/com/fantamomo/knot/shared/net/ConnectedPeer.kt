package com.fantamomo.knot.shared.net

import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.connection.Client
import com.fantamomo.knot.shared.net.mplex.LogicalStream
import com.fantamomo.knot.shared.net.mplex.MplexTransport
import com.fantamomo.knot.shared.net.packets.PacketEnvelop
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ConnectedPeer(
    val network: Network,
    val peerId: PeerId,
    val transport: MplexTransport,
    val client: Client
) {
    private val streamMutex: ConcurrentHashMap<PacketStreamId, Mutex> = ConcurrentHashMap()

    private val started = atomic(false)

    fun start() {
        if (!started.compareAndSet(expect = false, update = true)) return
        transport.openStreams.forEach { stream ->
            launchListener(stream)
        }
        transport.addIncomingStreamListener {
            launchListener(it)
        }
    }

    private fun launchListener(stream: LogicalStream) {
        network.scope.launch {
            stream.incomingPackets().collect { envelop ->
                network.handlePacket(this@ConnectedPeer, envelop)
            }
        }
    }

    suspend fun send(envelop: PacketEnvelop) {
        val stream = getStream(envelop.codec.streamId)
        stream.send(envelop)
    }

    private suspend fun getStream(id: PacketStreamId): LogicalStream {
        getOpenStream(id.name)?.let { return it }
        val mutex = streamMutex.getOrPut(id) { Mutex() }
        mutex.withLock {
            val openStream = getOpenStream(id.name)
            if (openStream != null) return openStream
            val newStream = transport.openStream(id.name)
            launchListener(newStream)
            return newStream
        }
    }

    private fun getOpenStream(name: String) = transport.openStreams.find { it.id == name }
}