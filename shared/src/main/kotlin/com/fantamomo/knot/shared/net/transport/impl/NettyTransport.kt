
package com.fantamomo.knot.shared.net.transport.impl

import com.fantamomo.knot.shared.net.transport.Transport
import com.fantamomo.knot.shared.utils.awaitResult
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class NettyTransport(
    private val channel: Channel,
    private val group: EventLoopGroup,
    private val incoming: IncomingBytes
) : Transport {

    private val closed = AtomicBoolean(false)

    override val remoteAddr: InetAddress
        get() = socketAddress(channel.remoteAddress()).address
            ?: error("Remote address is unresolved")

    override val localAddr: InetAddress
        get() = socketAddress(channel.localAddress()).address
            ?: error("Local address is unresolved")

    override suspend fun awaitReadable() {
        incoming.awaitReadable()
    }

    override suspend fun write(bytes: ByteArray) {
        check(!closed.get()) { "Transport is closed" }

        if (bytes.isEmpty()) return

        val buffer = channel.alloc().buffer(bytes.size)
        buffer.writeBytes(bytes)

        try {
            channel.writeAndFlush(buffer).awaitResult()
        } catch (t: Throwable) {
            if (buffer.refCnt() > 0) {
                buffer.release()
            }
            throw t
        }
    }

    override suspend fun writeFrom(source: Source) {
        val buffer = Buffer()
        val chunkSize = 8192L

        while (true) {
            val count = source.readAtMostTo(buffer, chunkSize)

            if (count == -1L) break
            if (count == 0L) continue

            val bytes = buffer.readByteArray(count.toInt())
            write(bytes)
        }
    }

    override suspend fun read(): ByteArray = incoming.readAvailable()

    override suspend fun read(length: Int): ByteArray {
        require(length >= 0) { "length must not be negative" }
        return incoming.readExactly(length)
    }

    override suspend fun readTo(destination: ByteArray) {
        val bytes = read(destination.size)
        bytes.copyInto(destination)
    }

    override suspend fun readTo(sink: Sink, length: Int) {
        require(length >= 0) { "length must not be negative" }

        var remaining = length

        while (remaining > 0) {
            val bytes = incoming.readUpTo(remaining)
            val buffer = Buffer()
            buffer.write(bytes)
            sink.write(buffer, bytes.size.toLong())
            remaining -= bytes.size
        }
    }

    override fun readAllTo(sink: Sink): Int {
        val bytes = incoming.drainAvailable()
        if (bytes.isEmpty()) return 0

        val buffer = Buffer()
        buffer.write(bytes)
        sink.write(buffer, bytes.size.toLong())

        return bytes.size
    }

    override suspend fun close(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return

        incoming.close(cause)

        try {
            channel.close().awaitResult()
        } finally {
            group.shutdownGracefully().awaitResult()
        }
    }

    private fun socketAddress(address: java.net.SocketAddress?): InetSocketAddress {
        return address as? InetSocketAddress
            ?: error("Channel has no Internet socket address")
    }

}

class IncomingBytes(
    private val maxBufferedBytes: Int
) {
    private val lock = Any()
    private val chunks = ArrayDeque<ByteArray>()

    private var firstOffset = 0
    private var bufferedBytes = 0
    private var terminalCause: Throwable? = null
    private var isClosed = false
    private var changed = CompletableDeferred<Unit>()

    fun offer(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true

        val signal: CompletableDeferred<Unit>

        synchronized(lock) {
            if (isClosed) return false

            if (bytes.size > maxBufferedBytes - bufferedBytes) {
                isClosed = true
                terminalCause = IOException("Receive buffer limit exceeded")
                signal = changed
                changed = CompletableDeferred()
            } else {
                chunks.addLast(bytes)
                bufferedBytes += bytes.size
                signal = changed
                changed = CompletableDeferred()
            }
        }

        signal.complete(Unit)

        return synchronized(lock) {
            terminalCause !is IOException ||
                    terminalCause?.message != "Receive buffer limit exceeded"
        }
    }

    fun close(cause: Throwable?) {
        val signal: CompletableDeferred<Unit>

        synchronized(lock) {
            if (isClosed) return

            isClosed = true
            terminalCause = cause
            signal = changed
            changed = CompletableDeferred()
        }

        signal.complete(Unit)
    }

    suspend fun awaitReadable() {
        while (true) {
            val signal = synchronized(lock) {
                if (bufferedBytes > 0) return
                if (isClosed) throw terminalError()
                changed
            }

            signal.await()
        }
    }

    suspend fun readAvailable(): ByteArray {
        while (true) {
            val result: ByteArray?
            val signal: CompletableDeferred<Unit>?

            synchronized(lock) {
                if (bufferedBytes > 0) {
                    result = takeLocked(bufferedBytes)
                    signal = null
                } else {
                    if (isClosed) throw terminalError()
                    result = null
                    signal = changed
                }
            }

            if (result != null) return result
            signal!!.await()
        }
    }

    suspend fun readExactly(length: Int): ByteArray {
        if (length == 0) return ByteArray(0)

        while (true) {
            val signal = synchronized(lock) {
                if (bufferedBytes >= length) {
                    return takeLocked(length)
                }

                if (isClosed) {
                    throw EOFException(
                        "Connection closed: requested $length bytes, " +
                                "but only $bufferedBytes remain"
                    ).also { terminalCause?.let(it::initCause) }
                }

                changed
            }

            signal.await()
        }
    }

    suspend fun readUpTo(maxLength: Int): ByteArray {
        require(maxLength > 0)

        while (true) {
            val signal = synchronized(lock) {
                if (bufferedBytes > 0) {
                    return takeLocked(minOf(maxLength, bufferedBytes))
                }

                if (isClosed) throw terminalError()
                changed
            }

            signal.await()
        }
    }

    fun drainAvailable(): ByteArray = synchronized(lock) {
        if (bufferedBytes == 0) return@synchronized ByteArray(0)
        takeLocked(bufferedBytes)
    }

    private fun takeLocked(length: Int): ByteArray {
        val result = ByteArray(length)
        var outputOffset = 0

        while (outputOffset < length) {
            val head = chunks.first()
            val count = minOf(
                length - outputOffset,
                head.size - firstOffset
            )

            head.copyInto(
                destination = result,
                destinationOffset = outputOffset,
                startIndex = firstOffset,
                endIndex = firstOffset + count
            )

            outputOffset += count
            firstOffset += count
            bufferedBytes -= count

            if (firstOffset == head.size) {
                chunks.removeFirst()
                firstOffset = 0
            }
        }

        return result
    }

    private fun terminalError(): Throwable = terminalCause ?: EOFException("Connection closed")
}
