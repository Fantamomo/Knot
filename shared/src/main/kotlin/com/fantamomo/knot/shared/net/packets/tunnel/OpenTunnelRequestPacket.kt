package com.fantamomo.knot.shared.net.packets.tunnel

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.readUuid
import com.fantamomo.knot.shared.utils.writeUuid
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.uuid.Uuid

data class OpenTunnelRequestPacket(
    val requestId: Uuid = Uuid.random(),
    val port: Int = 0,
    val host: String = "0.0.0.0"
) : TunnelPacket() {

    init {
        require(port in 0..65535) { "Port must be between 0 and 65535" }
    }

    companion object : PacketCodec<OpenTunnelRequestPacket> {
        override val packetClass = OpenTunnelRequestPacket::class
        override val id = "tunnel/open_request"
        override val streamId = PacketStreamId.DATA_RAW_TCP

        override fun encode(
            packet: OpenTunnelRequestPacket,
            sink: Sink
        ) {
            sink.writeUuid(packet.requestId)
            sink.writeInt(packet.port)
            sink.writeString(packet.host)
        }

        override fun decode(source: Source): OpenTunnelRequestPacket {
            return OpenTunnelRequestPacket(
                requestId = source.readUuid(),
                port = source.readInt(),
                host = source.readString()
            )
        }
    }
}