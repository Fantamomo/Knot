package com.fantamomo.knot.shared.net.packets

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

@Suppress("UNCHECKED_CAST")
fun <P : Packet> P.getCodec(): PacketCodec<P> = PacketRegistry.getCodec(this::class) as PacketCodec<P>

fun <P : Packet> PacketCodec<P>.encodeToByteArray(packet: P): ByteArray {
    val buffer = Buffer()
    encode(packet, buffer)
    return buffer.readByteArray()
}

fun <P : Packet> PacketCodec<P>.decodeFromByteArray(bytes: ByteArray): P {
    val buffer = Buffer()
    return decode(buffer)
}