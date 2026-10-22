package com.fantamomo.knot.shared.net.mplex.impl

import com.fantamomo.knot.shared.net.mplex.LogicalStream
import com.fantamomo.knot.shared.net.mplex.MplexTransport
import com.fantamomo.knot.shared.net.packets.PacketEnvelop
import com.fantamomo.knot.shared.net.security.SecureTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer

class DirectMplexTransport(override val transport: SecureTransport) : MplexTransport {
    override val openStreams: Set<LogicalStream>
        field: MutableSet<DirectLogicalStream> = mutableSetOf()

    private val mutex = Mutex()

    private val listeners = mutableListOf<(LogicalStream) -> Unit>()

    private val inbox = Channel<PacketEnvelop>(
        capacity = Channel.UNLIMITED,
    )

    override suspend fun run() = coroutineScope {
        while (true) {
            val packet = PacketEnvelop.readFrom(transport)
            if (openStreams.isEmpty()) {
                val stream = DirectLogicalStream(this@DirectMplexTransport, "")
                openStreams.add(stream)
                launch { listeners.forEach { it.invoke(stream) } }
            }
            inbox.send(packet)
        }
    }

    override suspend fun openStream(name: String): LogicalStream {
        require(name.isNotBlank()) { "Stream name cannot be blank" }
        val stream = DirectLogicalStream(this, name)
        openStreams.add(stream)
        return stream
    }

    override fun addIncomingStreamListener(listener: (LogicalStream) -> Unit) {
        listeners.add(listener)
    }

    private suspend fun send(packet: PacketEnvelop) {
        val buffer = Buffer()
        PacketEnvelop.encodeTo(packet, buffer)
        mutex.withLock {
            transport.writeFrom(buffer)
        }
    }

    class DirectLogicalStream(
        override val transport: DirectMplexTransport,
        override val id: String,
    ) : LogicalStream {
        private var closed = false
        private var closedCause: Throwable? = null

        override suspend fun send(packet: PacketEnvelop) {
            if (closed) {
                throw closedCause ?: IllegalStateException("Stream is closed")
            }
            transport.send(packet)
        }

        override fun incomingPackets() = transport.inbox.receiveAsFlow()

        override suspend fun close(cause: Throwable?) {
            if (closed) return
            closed = true
            closedCause = cause
        }

    }
}