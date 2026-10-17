package com.fantamomo.knot.shared.net.connection

interface Server {

    suspend fun start()

    suspend fun shutdown()

    suspend fun accept(): Client
}