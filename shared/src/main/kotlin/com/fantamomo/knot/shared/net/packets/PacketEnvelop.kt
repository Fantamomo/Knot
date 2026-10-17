package com.fantamomo.knot.shared.net.packets

import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.security.SecureTransport
import com.fantamomo.knot.shared.utils.*
import kotlinx.io.*
import kotlin.uuid.Uuid

@ConsistentCopyVisibility
data class PacketEnvelop private constructor(
    val id: Uuid = Uuid.random(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: PeerId,
    val target: PeerId,
    val ttl: Byte = DEFAULT_TTL,
    val trace: List<PeerId> = listOf(source),
    val codec: PacketCodec<Packet>,
    private val value: Any? = null
) {
    private var packetOrNull: Packet? = value as? Packet
    private var dataOrNull: ByteArray? = value as? ByteArray

    @Suppress("UNCHECKED_CAST")
    constructor(packet: Packet, source: PeerId, target: PeerId) : this(
        codec = PacketRegistry.getCodec(packet::class) as PacketCodec<Packet>,
        value = packet,
        source = source,
        target = target
    )

    @Suppress("UNCHECKED_CAST")
    constructor(codec: PacketCodec<*>, data: ByteArray, source: PeerId, target: PeerId) : this(
        codec = codec as PacketCodec<Packet>,
        value = data,
        source = source,
        target = target
    )

    init {
        require(packetOrNull != null || dataOrNull != null) {
            "Neither packet nor data is provided"
        }
    }

    fun withNextHop(localPeerId: PeerId): PacketEnvelop {
        val envelop = copy(
            ttl = (ttl - 1).toByte(), // fascinating, did you know that Byte minus Byte is Int???
            trace = trace + localPeerId
        )
        envelop.packetOrNull = packetOrNull
        envelop.dataOrNull = dataOrNull
        return envelop
    }

    val packet: Packet
        get() = getOrCreatePacket()

    val data: ByteArray
        get() = getOrCreateData()

    private fun getOrCreateData(): ByteArray {
        dataOrNull?.let { return it }

        val packet = packetOrNull
            ?: throw IllegalStateException("Packet is null")

        val buffer = Buffer()
        codec.encode(packet, buffer)

        return buffer.readByteArray().also {
            dataOrNull = it
        }
    }

    private fun getOrCreatePacket(): Packet {
        packetOrNull?.let { return it }

        val data = dataOrNull
            ?: throw IllegalStateException("Data is null")

        val buffer = Buffer()
        buffer.write(data)

        return codec.decode(buffer).also {
            packetOrNull = it
        }
    }

    companion object {
        private const val UUID_SIZE = 16L
        private const val TIMESTAMP_SIZE = 8L
        private const val LENGTH_SIZE = 2L
        private const val HEADER_SIZE = UUID_SIZE + TIMESTAMP_SIZE

        const val DEFAULT_TTL = 16.toByte()

        /*
         * Packet Envelop
         * | name       | size (in bytes) |
         * |------------|-----------------|
         * | id         | 16              |
         * | timestamp  | 8               |
         * | source     | 16              |
         * | target     | 16              |
         * | ttl        | 1               |
         * | trace size | 1               |
         * | trace      | 16 * trace size |
         * | codec id   | variable        |
         * | data       | variable        |
         */

        fun encodeTo(envelop: PacketEnvelop, sink: Sink) {
            sink.writeUuid(envelop.id)
            sink.writeLong(envelop.timestamp)
            sink.write(envelop.source.id)
            sink.write(envelop.target.id)
            sink.writeByte(envelop.ttl)
            sink.writeByte(envelop.trace.size.toByte())
            for ((id) in envelop.trace) {
                sink.write(id)
            }
            sink.writeSmartString(envelop.codec.id)
            sink.writeSmartBytes(envelop.data)
        }

        fun decodeFrom(source: Source): PacketEnvelop {
            val id = source.readUuid()
            val timestamp = source.readLong()
            val sourceId = source.readByteArray(PeerId.SIZE)
            val target = source.readByteArray(PeerId.SIZE)
            val ttl = source.readByte()
            val traceSize = source.readByte()
            val trace = List(traceSize.toInt()) {
                PeerId(source.readByteArray(PeerId.SIZE))
            }
            val codecId = source.readSmartString()
            val data = source.readSmartBytes()

            @Suppress("UNCHECKED_CAST")
            val codec = PacketRegistry.getCodec(codecId) as PacketCodec<Packet>

            return PacketEnvelop(id, timestamp, PeerId(sourceId), PeerId(target), ttl, trace, codec, data)
        }

        fun tryReadFrom(source: Buffer): PacketEnvelop? {
            @OptIn(InternalIoApi::class)
            val peek = source.peek().buffer

            // UUID + timestamp
            if (peek.size < HEADER_SIZE) return null
            peek.skip(HEADER_SIZE)

            // Source
            if (peek.size < PeerId.SIZE) return null
            peek.skip(PeerId.SIZE.toLong())

            // Target
            if (peek.size < PeerId.SIZE) return null
            peek.skip(PeerId.SIZE.toLong())

            // TTL
            if (peek.size < 1) return null
            peek.skip(1)

            // Trace
            if (peek.size < 1) return null
            val traceSize = peek.readByte()
            peek.skip(traceSize.toLong() * PeerId.SIZE)

            // Codec ID
            val codecLength = readSmartFieldLength(peek) ?: return null
            if (peek.size < codecLength) return null
            peek.skip(codecLength)

            // Payload
            val payloadLength = readSmartFieldLength(peek) ?: return null
            if (peek.size < payloadLength) return null
            peek.skip(payloadLength)

            val consumed = source.size - peek.size

            val packetBuffer = Buffer()
            source.readAtMostTo(packetBuffer, consumed)

            return decodeFrom(packetBuffer)
        }

        suspend fun readFrom(transport: SecureTransport): PacketEnvelop {
            val buffer = Buffer()

            suspend fun readBytes(length: Long) {
                if (length > 0L) {
                    transport.readTo(buffer, length = length.toInt())
                }
            }

            suspend fun readSmartString(): String {
                readBytes(LENGTH_SIZE)

                val length = buffer.readUShort().toInt()
                readBytes(length.toLong())

                return buffer.readByteArray(length).decodeToString()
            }

            suspend fun readSmartBytes(): ByteArray {
                readBytes(LENGTH_SIZE)

                val length = buffer.readUShort().toInt()
                readBytes(length.toLong())

                return buffer.readByteArray(length)
            }

            readBytes(UUID_SIZE)
            val id = buffer.readUuid()

            readBytes(TIMESTAMP_SIZE)
            val timestamp = buffer.readLong()

            readBytes(PeerId.SIZE.toLong())
            val sourceId = buffer.readByteArray(PeerId.SIZE)

            readBytes(PeerId.SIZE.toLong())
            val target = buffer.readByteArray(PeerId.SIZE)

            readBytes(1)
            val ttl = buffer.readByte()

            readBytes(1)
            val traceSize = buffer.readByte()
            val trace = List(traceSize.toInt()) {
                readBytes(PeerId.SIZE.toLong())
                PeerId(buffer.readByteArray(PeerId.SIZE))
            }

            val codecId = readSmartString()

            @Suppress("UNCHECKED_CAST")
            val codec = PacketRegistry.getCodec(codecId) as PacketCodec<Packet>

            val data = readSmartBytes()

            return PacketEnvelop(id, timestamp, PeerId(sourceId), PeerId(target), ttl, trace, codec, data)
        }

        private fun readSmartFieldLength(source: Source): Long? {
            if (!source.request(LENGTH_SIZE)) return null

            return source.readUShort().toLong()
        }
    }
}