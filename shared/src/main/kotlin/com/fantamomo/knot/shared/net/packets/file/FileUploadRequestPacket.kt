package com.fantamomo.knot.shared.net.packets.file

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.readSmartString
import com.fantamomo.knot.shared.utils.readUuid
import com.fantamomo.knot.shared.utils.writeSmartString
import com.fantamomo.knot.shared.utils.writeUuid
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readULong
import kotlinx.io.writeULong
import kotlin.uuid.Uuid

data class FileUploadRequestPacket(
    val requestId: Uuid,
    val fileName: String,
    val expectedFileSize: ULong
) : FilePacket() {
    companion object : PacketCodec<FileUploadRequestPacket> {
        override val packetClass = FileUploadRequestPacket::class
        override val id = "file/upload_request"
        override val streamId = PacketStreamId.DATA_FILE

        override fun encode(
            packet: FileUploadRequestPacket,
            sink: Sink
        ) {
            sink.writeUuid(packet.requestId)
            sink.writeSmartString(packet.fileName)
            sink.writeULong(packet.expectedFileSize)
        }

        override fun decode(source: Source): FileUploadRequestPacket {
            val requestId = source.readUuid()
            val fileName = source.readSmartString()
            val expectedFileSize = source.readULong()
            return FileUploadRequestPacket(requestId, fileName, expectedFileSize)
        }
    }
}