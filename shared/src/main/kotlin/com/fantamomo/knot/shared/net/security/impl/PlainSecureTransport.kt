package com.fantamomo.knot.shared.net.security.impl

import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.security.SecureTransport
import com.fantamomo.knot.shared.net.transport.Transport
import kotlinx.io.Sink
import kotlinx.io.Source

class PlainSecureTransport(
    override val transport: Transport,
    override val localPeerId: PeerId
) : SecureTransport {

    private var receivedPeerId: PeerId? = null

    override val remotePeerId: PeerId
        get() = checkNotNull(receivedPeerId) {
            "Remote PeerId is not available before the handshake"
        }

    override suspend fun start() {
        // Keine Initialisierung notwendig.
    }

    override suspend fun doClientHandshake() {
        check(receivedPeerId == null) {
            "Handshake has already completed"
        }

        transport.write(localPeerId.id)

        val remoteId = transport.read(PeerId.SIZE)
        receivedPeerId = PeerId(remoteId)
    }

    override suspend fun doServerHandshake() {
        check(receivedPeerId == null) {
            "Handshake has already completed"
        }

        val remoteId = transport.read(PeerId.SIZE)
        receivedPeerId = PeerId(remoteId)

        transport.write(localPeerId.id)
    }

    override suspend fun awaitReadable() = transport.awaitReadable()

    override suspend fun write(bytes: ByteArray) = transport.write(bytes)

    override suspend fun writeFrom(source: Source) = transport.writeFrom(source)

    override suspend fun read(): ByteArray = transport.read()

    override suspend fun read(length: Int): ByteArray = transport.read(length)

    override suspend fun readTo(destination: ByteArray) = transport.readTo(destination)

    override suspend fun readTo(
        sink: Sink,
        length: Int
    ) = transport.readTo(sink, length)

    override fun readAllTo(sink: Sink): Int = transport.readAllTo(sink)

    override suspend fun close(cause: Throwable?) {
        transport.close(cause)
    }

    private companion object {
        const val PEER_ID_SIZE = 16
    }
}