package com.fantamomo.knot.shared.net.packets.internal

import com.fantamomo.knot.shared.model.PeerAddress
import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.*
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.reflect.KClass

/**
 * Sent by a relay that sees heavy traffic between two peers. The receiver is asked to open a direct connection to [peerId].
 */
data class DirectConnectHint(
    val peerId: PeerId,
    val addresses: List<PeerAddress>
) : ManagementPacket() {
    companion object : PacketCodec<DirectConnectHint> {
        override val packetClass: KClass<DirectConnectHint> = DirectConnectHint::class
        override val id: String = "management/direct-connect-hint"
        override val streamId: PacketStreamId = PacketStreamId.INTERNAL

        override fun encode(packet: DirectConnectHint, sink: Sink) {
            sink.writePeerId(packet.peerId)
            sink.writeList(packet.addresses) { writePeerAddress(it) }
        }

        override fun decode(source: Source): DirectConnectHint {
            return DirectConnectHint(
                peerId = source.readPeerId(),
                addresses = source.readList { readPeerAddress() }
            )
        }
    }
}