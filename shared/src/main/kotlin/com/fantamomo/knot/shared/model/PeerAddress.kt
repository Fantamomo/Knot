package com.fantamomo.knot.shared.model

data class PeerAddress(val address: String, val port: Int?) {
    init {
        require(port in 0..65535) { "Port must be between 0 and 65535" }
    }
}