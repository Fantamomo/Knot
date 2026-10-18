package com.fantamomo.knot.shared.net.transport

import kotlinx.io.Sink
import kotlinx.io.Source
import java.net.InetAddress

interface Transport {

    val remoteAddr: InetAddress
    val remotePort: Int
    val localAddr: InetAddress
    val localPort: Int

    suspend fun start() {}

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