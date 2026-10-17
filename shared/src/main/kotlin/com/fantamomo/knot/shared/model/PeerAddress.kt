package com.fantamomo.knot.shared.model

import io.libp2p.core.multiformats.Protocol

sealed interface PeerAddress {
    val address: String
    val port: Int?
    val supportedProtocols: List<Protocol>
}
