package com.fantamomo.knot.shared.net.util

import com.fantamomo.knot.shared.net.Network
import com.fantamomo.knot.shared.net.packets.IncomingPacket
import com.fantamomo.knot.shared.net.packets.Packet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

@Suppress("UNCHECKED_CAST")
inline fun <reified T : Packet> Network.incomingPackets(): Flow<IncomingPacket<T>> =
    incomingPackets().filter { it.packet is T } as Flow<IncomingPacket<T>>