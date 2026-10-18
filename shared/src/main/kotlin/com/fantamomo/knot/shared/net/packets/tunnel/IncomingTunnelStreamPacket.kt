package com.fantamomo.knot.shared.net.packets.tunnel

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.*
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.uuid.Uuid

data class IncomingTunnelStreamPacket(
    val tunnelId: Uuid,
    val sessionId: Uuid,
    val sourceAddress: String,
    val data: ByteArray,
) : TunnelPacket() {

    init {
        require(data.size <= 1024) { "Data size must be less than or equal to 1024 bytes" }
    }

    companion object : PacketCodec<IncomingTunnelStreamPacket> {
        override val packetClass = IncomingTunnelStreamPacket::class
        override val id = "tunnel/stream_incoming"
        override val streamId = PacketStreamId.DATA_RAW_TCP

        override fun encode(
            packet: IncomingTunnelStreamPacket,
            sink: Sink
        ) {
            sink.writeUuid(packet.tunnelId)
            sink.writeUuid(packet.sessionId)
            sink.writeSmartString(packet.sourceAddress)
            sink.writeSmartBytes(packet.data)
        }

        override fun decode(source: Source): IncomingTunnelStreamPacket {
            val tunnelId = source.readUuid()
            val requestId = source.readUuid()
            val sourceAddress = source.readSmartString()
            val data = source.readSmartBytes()
            return IncomingTunnelStreamPacket(tunnelId, requestId, sourceAddress, data)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IncomingTunnelStreamPacket

        if (tunnelId != other.tunnelId) return false
        if (sessionId != other.sessionId) return false
        if (sourceAddress != other.sourceAddress) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tunnelId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + sourceAddress.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}