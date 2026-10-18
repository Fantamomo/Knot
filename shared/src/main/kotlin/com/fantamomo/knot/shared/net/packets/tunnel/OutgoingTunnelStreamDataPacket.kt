package com.fantamomo.knot.shared.net.packets.tunnel

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.readSmartBytes
import com.fantamomo.knot.shared.utils.readUuid
import com.fantamomo.knot.shared.utils.writeSmartBytes
import com.fantamomo.knot.shared.utils.writeUuid
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.uuid.Uuid

data class OutgoingTunnelStreamDataPacket(
    val sessionId: Uuid,
    val data: ByteArray,
) : TunnelPacket() {
    companion object : PacketCodec<OutgoingTunnelStreamDataPacket> {
        override val packetClass = OutgoingTunnelStreamDataPacket::class
        override val id = "tunnel/stream_outgoing_data"
        override val streamId = PacketStreamId.DATA_RAW_TCP

        override fun encode(
            packet: OutgoingTunnelStreamDataPacket,
            sink: Sink
        ) {
            sink.writeUuid(packet.sessionId)
            sink.writeSmartBytes(packet.data)
        }

        override fun decode(source: Source): OutgoingTunnelStreamDataPacket {
            return OutgoingTunnelStreamDataPacket(
                sessionId = source.readUuid(),
                data = source.readSmartBytes(),
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OutgoingTunnelStreamDataPacket

        if (sessionId != other.sessionId) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}