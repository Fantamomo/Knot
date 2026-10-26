package com.fantamomo.knot.shared.net.packets.tunnel

import com.fantamomo.knot.shared.net.packets.Packet
import com.fantamomo.knot.shared.net.packets.PacketRegistry

sealed class TunnelPacket : Packet() {
    companion object {
        fun register() {
            PacketRegistry.register(CloseStreamPacket)
            PacketRegistry.register(IncomingTunnelStreamDataPacket)
            PacketRegistry.register(IncomingTunnelStreamPacket)
            PacketRegistry.register(OpenTunnelRequestPacket)
            PacketRegistry.register(OpenTunnelResponsePacket)
            PacketRegistry.register(OutgoingTunnelStreamDataPacket)
        }
    }
}