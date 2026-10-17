package com.fantamomo.knot.shared.net.mplex

import com.fantamomo.knot.shared.net.security.SecureTransport

interface MplexTransport {
    val transport: SecureTransport

    val openStreams: Set<LogicalStream>

    suspend fun start() {}

    suspend fun run() {}

    suspend fun openStream(name: String): LogicalStream

    fun addIncomingStreamListener(listener: (LogicalStream) -> Unit)
}