package com.fantamomo.knot.shared.net.packets.file

import com.fantamomo.knot.shared.net.packets.Packet
import com.fantamomo.knot.shared.net.packets.PacketRegistry

sealed class FilePacket : Packet() {
    companion object {
        fun register() {
            PacketRegistry.register(FileUploadRequestPacket)
            PacketRegistry.register(FileUploadRequestResponsePacket)
            PacketRegistry.register(FinishFileUploadPacket)
            PacketRegistry.register(UploadFileChunkPacket)
        }
    }
}