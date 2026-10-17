package com.fantamomo.knot.shared.net.packets.internal

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.reflect.KClass

data class PongPacket(
    /**
     * The timestamp of the pong packet.
     */
    val timestamp: Long,
    /**
     * The timestamp of the ping packet that this pong packet is responding to.
     */
    val pingTimestamp: Long
) : ManagementPacket() {
    companion object : PacketCodec<PongPacket> {
        override val packetClass: KClass<PongPacket> = PongPacket::class
        override val id: String = "pong"
        override val streamId: PacketStreamId = PacketStreamId.INTERNAL

        override fun encode(packet: PongPacket, sink: Sink) {
            sink.writeLong(packet.timestamp)
            sink.writeLong(packet.pingTimestamp)
        }

        override fun decode(source: Source) = PongPacket(source.readLong(), source.readLong())
    }
}