package com.fantamomo.knot.shared.net.packets.internal

import com.fantamomo.knot.shared.model.PeerId

data class PeerAdvertisement(
    val peerId: PeerId,
    val knownAddresses: List<PeerAddress>
) : ManagementPacket() {
}