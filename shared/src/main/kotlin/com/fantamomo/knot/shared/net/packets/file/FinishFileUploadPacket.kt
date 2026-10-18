package com.fantamomo.knot.shared.net.packets.file

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.readUuid
import com.fantamomo.knot.shared.utils.writeUuid
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.uuid.Uuid

data class FinishFileUploadPacket(
    val fileId: Uuid,
    val chunks: Int,
    val size: Long
) : FilePacket() {
    companion object : PacketCodec<FinishFileUploadPacket> {
        override val packetClass = FinishFileUploadPacket::class
        override val id = "file/finish"
        override val streamId = PacketStreamId.DATA_FILE

        override fun encode(
            packet: FinishFileUploadPacket,
            sink: Sink
        ) {
            sink.writeUuid(packet.fileId)
            sink.writeInt(packet.chunks)
            sink.writeLong(packet.size)
        }

        override fun decode(source: Source): FinishFileUploadPacket {
            val fileId = source.readUuid()
            val chunks = source.readInt()
            val size = source.readLong()
            return FinishFileUploadPacket(fileId, chunks, size)
        }
    }
}