package com.fantamomo.knot.shared.net.packets.file

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.*
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.uuid.Uuid

data class FileUploadRequestResponsePacket(
    val requestId: Uuid,
    val success: Boolean,
    val reason: String?,
    val uploadId: Uuid?
) : FilePacket() {

    init {
        if (success && uploadId == null) throw IllegalArgumentException("uploadId must be provided if success is true")
        if (!success && uploadId != null) throw IllegalArgumentException("uploadId must not be provided if success is false")
        if (success && reason != null) throw IllegalArgumentException("reason must not be provided if success is true")
        if (!success && reason == null) throw IllegalArgumentException("reason must be provided if success is false")
    }

    companion object : PacketCodec<FileUploadRequestResponsePacket> {
        override val packetClass = FileUploadRequestResponsePacket::class
        override val id: String = "file/upload_request_response"
        override val streamId = PacketStreamId.DATA_FILE

        override fun encode(
            packet: FileUploadRequestResponsePacket,
            sink: Sink
        ) {
            sink.writeUuid(packet.requestId)
            sink.writeBoolean(packet.success)
            if (packet.success) {
                sink.writeUuid(packet.uploadId!!)
            } else {
                sink.writeSmartString(packet.reason!!)
            }
        }

        override fun decode(source: Source): FileUploadRequestResponsePacket {
            val requestId = source.readUuid()
            val success = source.readBoolean()
            val error = if (!success) source.readSmartString() else null
            val uploadId = if (success) source.readUuid() else null
            return FileUploadRequestResponsePacket(requestId, success, error, uploadId)
        }
    }
}