package com.fantamomo.knot.shared.net.packets.file

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.readSmartBytes
import com.fantamomo.knot.shared.utils.readUuid
import com.fantamomo.knot.shared.utils.writeSmartBytes
import com.fantamomo.knot.shared.utils.writeUuid
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.uuid.Uuid

data class UploadFileChunkPacket(
    val uploadId: Uuid,
    val sequenceNumber: Int, // staring with 0
    val chunk: ByteArray
) : FilePacket() {

    init {
        require(chunk.size <= 1024) { "Chunk size must be less than or equal to 1024 bytes" }
        require(sequenceNumber >= 0) { "Sequence number must be greater than or equal to 0" }
    }

    companion object : PacketCodec<UploadFileChunkPacket> {
        override val packetClass = UploadFileChunkPacket::class
        override val id = "file/chunk"
        override val streamId = PacketStreamId.DATA_FILE

        override fun encode(
            packet: UploadFileChunkPacket,
            sink: Sink
        ) {
            sink.writeUuid(packet.uploadId)
            sink.writeInt(packet.sequenceNumber)
            sink.writeSmartBytes(packet.chunk)
        }

        override fun decode(source: Source): UploadFileChunkPacket {
            val uploadId = source.readUuid()
            val sequenceNumber = source.readInt()
            val chunk = source.readSmartBytes()
            return UploadFileChunkPacket(uploadId, sequenceNumber, chunk)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UploadFileChunkPacket

        if (sequenceNumber != other.sequenceNumber) return false
        if (uploadId != other.uploadId) return false
        if (!chunk.contentEquals(other.chunk)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sequenceNumber
        result = 31 * result + uploadId.hashCode()
        result = 31 * result + chunk.contentHashCode()
        return result
    }
}