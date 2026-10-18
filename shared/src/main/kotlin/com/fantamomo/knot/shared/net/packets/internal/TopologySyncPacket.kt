package com.fantamomo.knot.shared.net.packets.internal

import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.readList
import com.fantamomo.knot.shared.utils.writeList
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.reflect.KClass

/**
 * A chunk of link-state records, sent to a freshly connected peer or as an answer to [RequestKnownPeersPacket].
 */
data class TopologySyncPacket(
    val advertisements: List<PeerAdvertisement>
) : ManagementPacket() {
    companion object : PacketCodec<TopologySyncPacket> {
        override val packetClass: KClass<TopologySyncPacket> = TopologySyncPacket::class
        override val id: String = "management/topology-sync"
        override val streamId: PacketStreamId = PacketStreamId.INTERNAL

        override fun encode(packet: TopologySyncPacket, sink: Sink) {
            sink.writeList(packet.advertisements) { PeerAdvertisement.encode(it, this) }
        }

        override fun decode(source: Source): TopologySyncPacket {
            return TopologySyncPacket(
                advertisements = source.readList { PeerAdvertisement.decode(this) }
            )
        }
    }
}