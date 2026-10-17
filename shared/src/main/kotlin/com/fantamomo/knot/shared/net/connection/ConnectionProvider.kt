package com.fantamomo.knot.shared.net.connection

interface ConnectionProvider {
    fun createServer(host: String, port: Int): Server

    suspend fun createClient(host: String, port: Int): Client
}