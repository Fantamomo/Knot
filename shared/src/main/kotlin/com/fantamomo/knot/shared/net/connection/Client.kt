package com.fantamomo.knot.shared.net.connection

import com.fantamomo.knot.shared.net.transport.Transport

interface Client {
    val transport: Transport

    suspend fun close(cause: Throwable?)
}