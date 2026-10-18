package com.fantamomo.knot.shared.net.packets.internal

import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.readList
import com.fantamomo.knot.shared.utils.readPeerId
import com.fantamomo.knot.shared.utils.writeList
import com.fantamomo.knot.shared.utils.writePeerId
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.reflect.KClass

data class KnownPeersPacket(
    val knownPeers: List<PeerId>
) : ManagementPacket() {
    companion object : PacketCodec<KnownPeersPacket> {
        override val packetClass: KClass<KnownPeersPacket> = KnownPeersPacket::class
        override val id: String = "management/known-peers"
        override val streamId: PacketStreamId = PacketStreamId.INTERNAL

        override fun encode(
            packet: KnownPeersPacket,
            sink: Sink
        ) {
            sink.writeList(packet.knownPeers) { writePeerId(it) }
        }

        override fun decode(source: Source): KnownPeersPacket {
            return KnownPeersPacket(
                knownPeers = source.readList { readPeerId() }
            )
        }

    }
}