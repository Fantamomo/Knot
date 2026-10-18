package com.fantamomo.knot.shared.net.packets.tunnel

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.*
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.uuid.Uuid

data class OpenTunnelResponsePacket(
    val requestId: Uuid,
    val success: Boolean,
    val reason: String?,
    val tunnelId: Uuid?,
    val host: String,
    val port: Int,
) : TunnelPacket() {

    init {
        if (success && tunnelId == null) throw IllegalArgumentException("tunnelId must be provided if success is true")
        if (!success && tunnelId != null) throw IllegalArgumentException("tunnelId must not be provided if success is false")
        if (success && reason != null) throw IllegalArgumentException("reason must not be provided if success is true")
        if (!success && reason == null) throw IllegalArgumentException("reason must be provided if success is false")
    }

    companion object : PacketCodec<OpenTunnelResponsePacket> {
        override val packetClass = OpenTunnelResponsePacket::class
        override val id = "tunnel/open_response"
        override val streamId = PacketStreamId.DATA_RAW_TCP

        override fun encode(
            packet: OpenTunnelResponsePacket,
            sink: Sink
        ) {
            sink.writeUuid(packet.requestId)
            sink.writeBoolean(packet.success)
            if (packet.success) {
                sink.writeUuid(packet.tunnelId!!)
            } else {
                sink.writeString(packet.reason!!)
            }
            sink.writeSmartString(packet.host)
            sink.writeInt(packet.port)
        }

        override fun decode(source: Source): OpenTunnelResponsePacket {
            val requestId = source.readUuid()
            val success = source.readBoolean()
            val reason = if (!success) source.readString() else null
            val tunnelId = if (success) source.readUuid() else null
            val host = source.readSmartString()
            val port = source.readInt()
            return OpenTunnelResponsePacket(requestId, success, reason, tunnelId, host, port)
        }
    }
}