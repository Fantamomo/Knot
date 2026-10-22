package com.fantamomo.knot.shared.net.mplex

import com.fantamomo.knot.shared.net.packets.PacketEnvelop
import kotlinx.coroutines.flow.Flow

interface LogicalStream {
    val transport: MplexTransport

    val id: String

    suspend fun send(packet: PacketEnvelop)

    fun incomingPackets(): Flow<PacketEnvelop>

    suspend fun close(cause: Throwable?)
}