package com.fantamomo.knot.shared.net.mplex.impl

import com.fantamomo.knot.shared.net.mplex.LogicalStream
import com.fantamomo.knot.shared.net.packets.PacketEnvelop
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean

class DefaultLogicalStream(
    override val transport: DefaultMplexTransport,
    override val id: String,
    override val internalId: Byte,
    capacity: Int = DefaultMplexTransport.STREAM_QUEUE_CAPACITY,
) : LogicalStream {

    init {
        require(capacity > 0) { "Stream capacity must be positive" }
    }

    private val closed = AtomicBoolean(false)

    private val inbox = Channel<PacketEnvelop>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override suspend fun send(packet: PacketEnvelop) {
        check(!closed.get()) { "Stream $internalId is closed" }

        transport.sendPacket(this, packet)
    }

    override fun incomingPackets(): Flow<PacketEnvelop> = inbox.receiveAsFlow()

    internal fun deliver(packet: PacketEnvelop) {
        if (closed.get()) return

        inbox.trySend(packet)
    }

    override suspend fun close(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return

        try {
            transport.closeStream(this)
        } finally {
            inbox.close(cause)
        }
    }

    internal fun markClosed(cause: Throwable? = null) {
        if (closed.compareAndSet(false, true)) {
            inbox.close(cause)
        }
    }

    internal val isClosed: Boolean
        get() = closed.get()
}