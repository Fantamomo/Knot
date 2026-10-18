package com.fantamomo.knot.shared.utils

import com.fantamomo.knot.shared.model.PeerAddress
import com.fantamomo.knot.shared.model.PeerId
import kotlinx.io.*
import kotlin.uuid.Uuid

fun Sink.writeUuid(uuid: Uuid) {
    uuid.toLongs { mostSignificantBits, leastSignificantBits ->
        writeLong(mostSignificantBits)
        writeLong(leastSignificantBits)
    }
}

fun Source.readUuid(): Uuid {
    val mostSignificantBits = readLong()
    val leastSignificantBits = readLong()
    return Uuid.fromLongs(mostSignificantBits, leastSignificantBits)
}

fun Sink.writeSmartString(string: String) {
    val byteLength = string.encodeToByteArray().size

    require(byteLength <= UShort.MAX_VALUE.toInt()) {
        "String is too large: $byteLength bytes (max ${UShort.MAX_VALUE.toInt()})"
    }

    writeUShort(byteLength.toUShort())

    writeString(string)
}

fun Source.readSmartString(): String {
    val length = readUShort()

    return readString(length.toLong())
}

fun Sink.writeSmartBytes(bytes: ByteArray) {
    val length = bytes.size

    require(length <= UShort.MAX_VALUE.toInt()) { "ByteArray is too large: $length bytes (max ${UShort.MAX_VALUE.toInt()})" }

    writeUShort(length.toUShort())

    write(bytes)
}

fun Source.readSmartBytes(): ByteArray {
    val length = readUShort()

    return readByteArray(length.toInt())
}

fun Sink.writePeerId(peerId: PeerId) = write(peerId.id)

fun Source.readPeerId() = PeerId(readByteArray(PeerId.SIZE))

fun Sink.writePeerAddress(peerAddress: PeerAddress) {
    writeSmartString(peerAddress.address)
    writeUShort((peerAddress.port ?: 0).toUShort())
}

fun Source.readPeerAddress(): PeerAddress {
    val address = readSmartString()
    val port = readUShort().toInt()

    return PeerAddress(address, port)
}

inline fun <E> Sink.writeList(list: List<E>, writer: Sink.(E) -> Unit) {
    writeUShort(list.size.toUShort())
    list.forEach { writer(it) }
}

inline fun <E> Source.readList(reader: Source.() -> E): List<E> {
    val size = readUShort().toInt()
    if (size == 0) return emptyList()
    return List(size) { reader() }
}