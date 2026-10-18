package com.fantamomo.knot.shared.net.packets.internal

import com.fantamomo.knot.shared.model.PeerAddress
import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketStreamId
import com.fantamomo.knot.shared.utils.*
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Link-state record of a peer. Flooded as a broadcast whenever the peer's connections change
 * and periodically as a refresh.
 */
data class PeerAdvertisement(
    /**
     * The local peer id.
     */
    val peerId: PeerId,
    /**
     * A list of addresses that other peers might use to connect to this peer.
     */
    val knownAddresses: List<PeerAddress>,
    /**
     * A list of peer ids that this peer is currently connected to.
     */
    val connectedPeers: List<PeerId>,
    /**
     * Monotonic version of this record, newer records replace older ones.
     */
    val sequence: Long
) : ManagementPacket() {

    companion object : PacketCodec<PeerAdvertisement> {
        override val packetClass = PeerAdvertisement::class
        override val id: String = "management/peer-advertisement"
        override val streamId = PacketStreamId.INTERNAL

        override fun encode(
            packet: PeerAdvertisement,
            sink: Sink
        ) {
            sink.writePeerId(packet.peerId)
            sink.writeList(packet.knownAddresses) { writePeerAddress(it) }
            sink.writeList(packet.connectedPeers) { writePeerId(it) }
            sink.writeLong(packet.sequence)
        }

        override fun decode(source: Source): PeerAdvertisement {
            return PeerAdvertisement(
                peerId = source.readPeerId(),
                knownAddresses = source.readList { readPeerAddress() },
                connectedPeers = source.readList { readPeerId() },
                sequence = source.readLong()
            )
        }
    }
}