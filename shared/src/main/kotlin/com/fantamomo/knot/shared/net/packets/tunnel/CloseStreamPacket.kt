package com.fantamomo.knot.shared.net.packets.tunnel

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.readUuid
import com.fantamomo.knot.shared.utils.writeUuid
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.uuid.Uuid

data class CloseStreamPacket(
    val sessionId: Uuid,
) : TunnelPacket() {
    companion object : PacketCodec<CloseStreamPacket> {
        override val packetClass = CloseStreamPacket::class
        override val id = "tunnel/close"
        override val streamId = PacketStreamId.DATA_RAW_TCP

        override fun encode(
            packet: CloseStreamPacket,
            sink: Sink
        ) {
            sink.writeUuid(packet.sessionId)
        }

        override fun decode(source: Source): CloseStreamPacket {
            return CloseStreamPacket(
                sessionId = source.readUuid()
            )
        }

    }
}