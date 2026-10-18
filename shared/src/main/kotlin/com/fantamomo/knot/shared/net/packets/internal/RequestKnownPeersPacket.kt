package com.fantamomo.knot.shared.net.packets.internal

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * The receiver of this packet is expected to respond with a [KnownPeersPacket].
 */
data object RequestKnownPeersPacket : ManagementPacket(), PacketCodec<RequestKnownPeersPacket> {
    override val packetClass = RequestKnownPeersPacket::class
    override val id: String = "management/request-known-peers"
    override val streamId: PacketStreamId = PacketStreamId.INTERNAL

    override fun encode(
        packet: RequestKnownPeersPacket,
        sink: Sink
    ) {}

    override fun decode(source: Source) = RequestKnownPeersPacket

}