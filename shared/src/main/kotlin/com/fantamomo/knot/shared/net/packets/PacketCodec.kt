package com.fantamomo.knot.shared.net.packets

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.reflect.KClass

interface PacketCodec<P : Packet> {
    val packetClass: KClass<P>
    val id: String
    val streamId: PacketStreamId

//    fun encode(packet: P): ByteArray
//    fun decode(bytes: ByteArray): P

    fun encode(packet: P, sink: Sink)
    fun decode(source: Source): P
}