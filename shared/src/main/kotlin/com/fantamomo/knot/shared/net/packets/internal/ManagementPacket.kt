package com.fantamomo.knot.shared.net.packets.internal

import com.fantamomo.knot.shared.net.packets.Packet
import com.fantamomo.knot.shared.net.packets.PacketRegistry

sealed class ManagementPacket : Packet() {
    companion object {
        fun register() {
            PacketRegistry.register(DirectConnectHint)
            PacketRegistry.register(KnownPeersPacket)
            PacketRegistry.register(PeerAdvertisement)
            PacketRegistry.register(PingPacket)
            PacketRegistry.register(PongPacket)
            PacketRegistry.register(RequestKnownPeersPacket)
            PacketRegistry.register(TopologySyncPacket)
        }
    }
}