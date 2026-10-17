package com.fantamomo.knot.shared.net.security

import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.transport.Transport
import kotlinx.io.Sink
import kotlinx.io.Source

interface SecureTransport {
    val transport: Transport

    val remotePeerId: PeerId
    val localPeerId: PeerId

    suspend fun start() {}

    suspend fun doClientHandshake() {}

    suspend fun doServerHandshake() {}

    suspend fun awaitReadable()

    suspend fun write(bytes: ByteArray)

    suspend fun writeFrom(source: Source)

    suspend fun read(): ByteArray

    suspend fun read(length: Int): ByteArray

    suspend fun readTo(destination: ByteArray)

    suspend fun readTo(sink: Sink, length: Int)

    fun readAllTo(sink: Sink): Int

    suspend fun close(cause: Throwable?)
}