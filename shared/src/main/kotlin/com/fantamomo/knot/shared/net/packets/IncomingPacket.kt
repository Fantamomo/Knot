package com.fantamomo.knot.shared.net.packets

import com.fantamomo.knot.shared.model.PeerId

data class IncomingPacket<P : Packet>(
    val packet: P,
    val envelop: PacketEnvelop
) {
    val source: PeerId
        get() = envelop.source
    val target: PeerId
        get() = envelop.target

    val isBroadcast: Boolean
        get() = envelop.target.isBroadcast
}