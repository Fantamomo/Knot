package com.fantamomo.knot.shared.net.packets.internal

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import kotlinx.io.Sink
import kotlinx.io.Source

data class PingPacket(val timestamp: Long) : ManagementPacket() {
    companion object : PacketCodec<PingPacket> {
        override val packetClass = PingPacket::class
        override val id: String = "ping"
        override val streamId: PacketStreamId = PacketStreamId.INTERNAL

        override fun encode(packet: PingPacket, sink: Sink) {
            sink.writeLong(packet.timestamp)
        }

        override fun decode(source: Source) = PingPacket(source.readLong())
    }
}