package com.fantamomo.knot.shared.net.mplex.impl

import com.fantamomo.knot.shared.net.mplex.LogicalStream
import com.fantamomo.knot.shared.net.mplex.MplexTransport
import com.fantamomo.knot.shared.net.packets.PacketEnvelop
import com.fantamomo.knot.shared.net.security.SecureTransport
import com.fantamomo.knot.shared.utils.writeSmartString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.readUShort
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds

class DefaultMplexTransport(
    override val transport: SecureTransport,
    private val localStreamIdParity: Int = 0,
) : MplexTransport {

    init {
        require(localStreamIdParity == 0 || localStreamIdParity == 1) {
            "localStreamIdParity must be 0 (even) or 1 (odd)"
        }
    }

    private val stateLock = Any()
    private val writerMutex = Mutex()

    private val streams = mutableMapOf<Byte, DefaultLogicalStream>()
    private val pendingOpens = mutableMapOf<Byte, CompletableDeferred<Byte>>()

    private val incomingStreamListeners = mutableListOf<(LogicalStream) -> Unit>()

    private var nextStreamId = localStreamIdParity
    private var allocatedLocalStreams = 0

    private var running = false
    private var terminalError: Throwable? = null

    override val openStreams: Set<LogicalStream>
        get() = synchronized(stateLock) {
            streams.values.toSet()
        }

    override suspend fun run() {
        synchronized(stateLock) {
            check(!running) {
                "Transport is already running"
            }
            running = true
        }

        var failure: Throwable? = null

        try {
            while (true) {
                when (val packetType = readByte()) {
                    PACKET_FORWARD -> handlePacketForward()
                    OPEN_STREAM_REQUEST -> handleOpenStreamRequest()
                    OPEN_STREAM_RESPONSE -> handleOpenStreamResponse()

                    else -> throw IllegalStateException("Unknown mplex packet type: $packetType")
                }
            }
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            synchronized(stateLock) {
                running = false
                terminalError = failure
            }

            shutdownStreams(
                failure ?: CancellationException("Mplex transport stopped")
            )

            if (failure != null && failure !is CancellationException) {
                try {
                    transport.close(failure)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private suspend fun readByte(): Byte {
        val buffer = Buffer()
        transport.awaitReadable()
        transport.readTo(buffer, 1)
        return buffer.readByte()
    }

    private suspend fun readBytes(length: Int): Buffer {
        require(length >= 0) { "Negative read length: $length" }

        val buffer = Buffer()
        if (length > 0) {
            transport.readTo(buffer, length)
        }
        return buffer
    }

    private suspend fun handlePacketForward() {
        val streamId = readByte()

        val stream = synchronized(stateLock) {
            streams[streamId]
        } ?: throw IllegalStateException(
            "Received PACKET_FORWARD for unknown stream id $streamId"
        )

        val envelope = PacketEnvelop.readFrom(transport)

        stream.deliver(envelope)
    }

    private suspend fun handleOpenStreamRequest() {
        val lengthBuffer = readBytes(2)
        val nameLength = lengthBuffer.readUShort().toInt()

        require(nameLength in 1..MAX_NAME_BYTES) { "Invalid stream name length: $nameLength" }

        val nameBuffer = readBytes(nameLength)
        val name = nameBuffer.readString(nameLength.toLong())

        val streamId = readByte()

        val expectedRemoteParity = 1 - localStreamIdParity

        val accepted = synchronized(stateLock) {
            running &&
                    (streamId.toInt() and 1) == expectedRemoteParity &&
                    streamId.toInt() in 0..MAX_STREAM_ID &&
                    streamId !in streams &&
                    streamId !in pendingOpens
        }

        if (!accepted) {
            writeFrame(
                Buffer().apply {
                    writeByte(OPEN_STREAM_RESPONSE)
                    writeByte(streamId)
                    writeByte(1)
                }
            )
            return
        }

        val stream = DefaultLogicalStream(
            transport = this,
            id = name,
            internalId = streamId,
            capacity = STREAM_QUEUE_CAPACITY,
        )

        val registered = synchronized(stateLock) {
            if (!running || streamId in streams || streamId in pendingOpens) {
                false
            } else {
                streams[streamId] = stream
                for (listener in incomingStreamListeners) {
                    try {
                        listener(stream)
                    } catch (e: Exception) {
                    }
                }
                true
            }
        }

        if (!registered) {
            stream.markClosed()
            writeFrame(
                Buffer().apply {
                    writeByte(OPEN_STREAM_RESPONSE)
                    writeByte(streamId)
                    writeByte(1)
                }
            )
            return
        }

        try {
            writeFrame(
                Buffer().apply {
                    writeByte(OPEN_STREAM_RESPONSE)
                    writeByte(streamId)
                    writeByte(0)
                }
            )
        } catch (e: Throwable) {
            removeStream(stream, e)
            throw e
        }
    }

    private suspend fun handleOpenStreamResponse() {
        val streamId = readByte()
        val status = readByte()

        val deferred = synchronized(stateLock) {
            pendingOpens[streamId]
        }

        if (deferred == null) return

        deferred.complete(status)
    }

    override suspend fun openStream(name: String): LogicalStream {
        require(name.isNotBlank()) { "Stream name must not be blank" }

        val encodedName = name.encodeToByteArray()
        require(encodedName.size in 1..MAX_NAME_BYTES) {
            "Stream name must be between 1 and $MAX_NAME_BYTES UTF-8 bytes"
        }

        val deferred = CompletableDeferred<Byte>()
        val streamId = synchronized(stateLock) {
            check(running) {
                "Transport is not running"
            }

            check(allocatedLocalStreams < MAX_STREAMS) {
                "Maximum number of local streams reached ($MAX_STREAMS)"
            }

            check(nextStreamId <= MAX_STREAM_ID) {
                "No stream IDs available"
            }

            val id = nextStreamId.toByte()
            nextStreamId += 2
            allocatedLocalStreams++
            pendingOpens[id] = deferred
            id
        }

        try {
            val frame = Buffer().apply {
                writeByte(OPEN_STREAM_REQUEST)
                writeSmartString(name)
                writeByte(streamId)
            }

            writeFrame(frame)

            val status = try {
                withTimeout(OPEN_TIMEOUT) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                throw TimeoutException(
                    "Timeout while opening stream '$name' (id=$streamId)"
                ).also { it.initCause(e) }
            }

            check(status == 0.toByte()) {
                "Peer rejected stream '$name' (id=$streamId, status=$status)"
            }

            val stream = DefaultLogicalStream(
                transport = this,
                id = name,
                internalId = streamId,
                capacity = STREAM_QUEUE_CAPACITY,
            )

            synchronized(stateLock) {
                check(running) {
                    "Transport stopped while opening stream '$name'"
                }
                check(streamId !in streams) {
                    "Stream ID collision: $streamId"
                }

                streams[streamId] = stream
            }

            return stream
        } catch (e: Throwable) {
            synchronized(stateLock) {
                if (pendingOpens[streamId] === deferred) {
                    pendingOpens.remove(streamId)
                }
            }
            throw e
        } finally {
            synchronized(stateLock) {
                if (pendingOpens[streamId] === deferred) {
                    pendingOpens.remove(streamId)
                }
            }
        }
    }

    override fun addIncomingStreamListener(listener: (LogicalStream) -> Unit) {
        incomingStreamListeners.add(listener)
    }

    private suspend fun writeFrame(frame: Buffer) {
        writerMutex.withLock {
            val error = synchronized(stateLock) {
                terminalError
            }

            if (error != null) {
                throw IllegalStateException(
                    "Mplex transport has failed",
                    error
                )
            }

            transport.writeFrom(frame)
        }
    }

    internal suspend fun sendPacket(
        logicalStream: DefaultLogicalStream,
        packetEnvelop: PacketEnvelop,
    ) {
        val frame = Buffer().apply {
            writeByte(PACKET_FORWARD)
            writeByte(logicalStream.internalId)
            PacketEnvelop.encodeTo(packetEnvelop, this)
        }

        writerMutex.withLock {
            val valid = synchronized(stateLock) {
                running &&
                        streams[logicalStream.internalId] === logicalStream &&
                        !logicalStream.isClosed
            }

            check(valid) {
                "Stream ${logicalStream.internalId} is not open"
            }

            transport.writeFrom(frame)
        }
    }

    internal suspend fun closeStream(stream: DefaultLogicalStream) {
        writerMutex.withLock {
            removeStream(stream, null)
        }
    }

    private fun removeStream(
        stream: DefaultLogicalStream,
        cause: Throwable?,
    ) {
        val removed = synchronized(stateLock) {
            if (streams[stream.internalId] === stream) {
                streams.remove(stream.internalId)
                true
            } else {
                false
            }
        }

        if (removed) {
            stream.markClosed(cause)
        }
    }

    private fun shutdownStreams(cause: Throwable) {
        val streamsToClose: List<DefaultLogicalStream>
        val pendingToFail: List<CompletableDeferred<Byte>>

        synchronized(stateLock) {
            streamsToClose = streams.values.toList()
            pendingToFail = pendingOpens.values.toList()

            streams.clear()
            pendingOpens.clear()
        }

        streamsToClose.forEach {
            it.markClosed(cause)
        }

        pendingToFail.forEach {
            it.completeExceptionally(cause)
        }
    }

    companion object {
        const val PACKET_FORWARD: Byte = 0x01
        const val OPEN_STREAM_REQUEST: Byte = 0x02
        const val OPEN_STREAM_RESPONSE: Byte = 0x03

        const val MAX_STREAMS = 50
        const val MAX_STREAM_ID = 99
        const val MAX_NAME_BYTES = 1024
        const val STREAM_QUEUE_CAPACITY = 64

        private val OPEN_TIMEOUT = 5.seconds
    }
}