package com.fantamomo.knot.shared.net.security.impl

import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.security.SecureTransport
import com.fantamomo.knot.shared.net.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*

class TlsSecureTransport(
    override val transport: Transport,
    override val localPeerId: PeerId,
    private val sslContext: SSLContext
) : SecureTransport {

    private val engine: SSLEngine =
        sslContext.createSSLEngine().apply {
            enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        }

    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val handshakeMutex = Mutex()

    @Volatile
    private var handshakeComplete = false

    private var started = false
    private var remoteId: PeerId? = null

    override val remotePeerId: PeerId
        get() = checkNotNull(remoteId) {
            "Remote PeerId is unavailable before the TLS handshake"
        }

    private var netIn = ByteBuffer.allocate(
        engine.session.packetBufferSize * 2
    ).apply { limit(0) }

    private var appIn = ByteBuffer.allocate(
        engine.session.applicationBufferSize * 2
    ).apply { limit(0) }

    override suspend fun start() {
        if (!started) {
            transport.start()
            started = true
        }
    }

    override suspend fun doClientHandshake() {
        performHandshake(client = true)
    }

    override suspend fun doServerHandshake() {
        performHandshake(client = false)
    }

    private suspend fun performHandshake(client: Boolean) {
        handshakeMutex.lock()
        try {
            check(!handshakeComplete) {
                "TLS handshake has already completed"
            }

            if (!started) start()

            engine.useClientMode = client

            if (!client) {
                engine.needClientAuth = true
            }

            engine.beginHandshake()

            while (true) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.FINISHED,
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                        break
                    }

                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        runDelegatedTasks()
                    }

                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        wrapAndSend(ByteArray(0))
                    }

                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP,
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                        val result = unwrapNetwork()

                        when (result.status) {
                            SSLEngineResult.Status.OK -> Unit

                            SSLEngineResult.Status.BUFFER_UNDERFLOW ->
                                receiveNetworkData()

                            SSLEngineResult.Status.BUFFER_OVERFLOW ->
                                growAppBuffer()

                            SSLEngineResult.Status.CLOSED ->
                                throw EOFException(
                                    "TLS peer closed during handshake"
                                )
                        }
                    }
                }
            }

            val certificates = engine.session.peerCertificates
            val certificate = certificates.firstOrNull() as? X509Certificate
                ?: throw SSLException("Peer did not present an X.509 certificate")

            // The configured TrustManager validates the CA chain.
            // The SAN binds the authenticated certificate to the PeerId.
            remoteId = peerIdFromCertificate(certificate)

            val localCertificates = engine.session.localCertificates
            val localCertificate =
                localCertificates?.firstOrNull() as? X509Certificate
                    ?: throw SSLException("Local certificate was not presented")

            check(peerIdFromCertificate(localCertificate) == localPeerId) {
                "Local PeerId does not match the local certificate"
            }

            handshakeComplete = true
        } finally {
            handshakeMutex.unlock()
        }
    }

    private suspend fun runDelegatedTasks() {
        val tasks = mutableListOf<Runnable>()

        while (true) {
            val task = engine.delegatedTask ?: break
            tasks += task
        }

        withContext(Dispatchers.Default) {
            tasks.forEach { it.run() }
        }
    }

    private suspend fun wrapAndSend(bytes: ByteArray) {
        var input = ByteBuffer.wrap(bytes)

        do {
            var output = ByteBuffer.allocate(
                engine.session.packetBufferSize * 2
            )

            val result = engine.wrap(input, output)

            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                output = ByteBuffer.allocate(
                    engine.session.packetBufferSize * 4
                )
                val retry = engine.wrap(input, output)

                if (retry.status != SSLEngineResult.Status.OK) {
                    throw SSLException("TLS wrap failed: ${retry.status}")
                }
                output.flip()
                if (output.hasRemaining()) {
                    val encrypted = ByteArray(output.remaining())
                    output.get(encrypted)
                    transport.write(encrypted)
                }
                continue
            }

            if (result.status == SSLEngineResult.Status.CLOSED &&
                input.hasRemaining()
            ) {
                throw EOFException("TLS output is closed")
            }

            if (result.status != SSLEngineResult.Status.OK &&
                result.status != SSLEngineResult.Status.CLOSED
            ) {
                throw SSLException("TLS wrap failed: ${result.status}")
            }

            output.flip()
            if (output.hasRemaining()) {
                val encrypted = ByteArray(output.remaining())
                output.get(encrypted)
                transport.write(encrypted)
            }

            if (result.handshakeStatus ==
                SSLEngineResult.HandshakeStatus.NEED_TASK
            ) {
                runDelegatedTasks()
            }
        } while (input.hasRemaining())
    }

    private fun unwrapNetwork(): SSLEngineResult {
        appIn.compact()

        val result = engine.unwrap(netIn, appIn)

        appIn.flip()
        return result
    }

    private suspend fun receiveNetworkData() {
        netIn.compact()

        val incoming = transport.read()
        if (incoming.isEmpty()) {
            throw EOFException("Transport returned EOF during TLS")
        }

        if (incoming.size > netIn.remaining()) {
            val newBuffer = ByteBuffer.allocate(
                maxOf(
                    netIn.capacity() * 2,
                    netIn.position() + incoming.size
                )
            )

            netIn.flip()
            newBuffer.put(netIn)
            netIn = newBuffer
        }

        netIn.put(incoming)
        netIn.flip()
    }

    private fun growAppBuffer() {
        val newBuffer = ByteBuffer.allocate(appIn.capacity() * 2)
        newBuffer.put(appIn)
        newBuffer.flip()
        appIn = newBuffer
    }

    private suspend fun readDecryptedAtLeastOne(): ByteArray {
        while (true) {
            if (appIn.hasRemaining()) {
                val result = ByteArray(appIn.remaining())
                appIn.get(result)
                return result
            }

            val result = unwrapNetwork()

            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    if (appIn.hasRemaining()) continue
                }

                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    receiveNetworkData()
                }

                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    growAppBuffer()
                }

                SSLEngineResult.Status.CLOSED -> {
                    throw EOFException("TLS peer closed the connection")
                }
            }
        }
    }

    private fun peerIdFromCertificate(
        certificate: X509Certificate
    ): PeerId {
        val sans = certificate.subjectAlternativeNames
            ?: throw SSLException("Certificate has no Subject Alternative Name")

        val uri = sans.asSequence()
            .filter { it.size >= 2 && it[0] == 6 }
            .mapNotNull { it[1] as? String }
            .singleOrNull { it.startsWith("urn:knot:peer:") }
            ?: throw SSLException("Certificate has no unique Knot PeerId URI")

        val hex = uri.removePrefix("urn:knot:peer:")

        if (hex.length != PeerId.SIZE * 2 ||
            hex.any { it.digitToIntOrNull(16) == null }
        ) {
            throw SSLException("Invalid PeerId in certificate")
        }

        val id = ByteArray(PeerId.SIZE) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

        return PeerId(id)
    }

    override suspend fun awaitReadable() {
        check(handshakeComplete) { "TLS handshake has not completed" }

        readMutex.lock()
        try {
            if (appIn.hasRemaining()) return

            while (true) {
                val result = unwrapNetwork()

                when (result.status) {
                    SSLEngineResult.Status.OK -> {
                        if (appIn.hasRemaining()) return
                    }

                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        receiveNetworkData()
                    }

                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        growAppBuffer()
                    }

                    SSLEngineResult.Status.CLOSED -> return
                }
            }
        } finally {
            readMutex.unlock()
        }
    }

    override suspend fun write(bytes: ByteArray) {
        check(handshakeComplete) { "TLS handshake has not completed" }

        writeMutex.lock()
        try {
            wrapAndSend(bytes)
        } finally {
            writeMutex.unlock()
        }
    }

    override suspend fun writeFrom(source: Source) {
        val buffer = Buffer()

        while (!source.exhausted()) {
            val count = source.readAtMostTo(buffer, 16_384L)
            if (count <= 0L) break
            write(buffer.readByteArray())
        }
    }

    override suspend fun read(): ByteArray {
        check(handshakeComplete) { "TLS handshake has not completed" }

        readMutex.lock()
        try {
            return readDecryptedAtLeastOne()
        } finally {
            readMutex.unlock()
        }
    }

    override suspend fun read(length: Int): ByteArray {
        require(length >= 0) { "length must not be negative" }
        check(handshakeComplete) { "TLS handshake has not completed" }

        val result = ByteArray(length)
        readTo(result)
        return result
    }

    override suspend fun readTo(destination: ByteArray) {
        check(handshakeComplete) { "TLS handshake has not completed" }

        readMutex.lock()
        try {
            var offset = 0

            while (offset < destination.size) {
                if (!appIn.hasRemaining()) {
                    val result = unwrapNetwork()

                    when (result.status) {
                        SSLEngineResult.Status.OK -> Unit
                        SSLEngineResult.Status.BUFFER_UNDERFLOW ->
                            receiveNetworkData()
                        SSLEngineResult.Status.BUFFER_OVERFLOW ->
                            growAppBuffer()
                        SSLEngineResult.Status.CLOSED ->
                            throw EOFException("TLS peer closed the connection")
                    }

                    continue
                }

                val count = minOf(
                    appIn.remaining(),
                    destination.size - offset
                )

                appIn.get(destination, offset, count)
                offset += count
            }
        } finally {
            readMutex.unlock()
        }
    }

    override suspend fun readTo(sink: Sink, length: Int) {
        val bytes = read(length)
        val buffer = Buffer().apply { write(bytes) }
        sink.write(buffer, bytes.size.toLong())
    }

    override fun readAllTo(sink: Sink): Int {
        // This API is synchronous: it cannot wait for network I/O.
        // It drains plaintext already buffered by the TLS engine.
        if (!handshakeComplete || !appIn.hasRemaining()) return 0

        val count = appIn.remaining()
        val bytes = ByteArray(count)
        appIn.get(bytes)

        val buffer = Buffer().apply { write(bytes) }
        sink.write(buffer, count.toLong())

        return count
    }

    override suspend fun close(cause: Throwable?) {
        try {
            if (handshakeComplete) {
                writeMutex.lock()
                try {
                    engine.closeOutbound()

                    val output = ByteBuffer.allocate(
                        engine.session.packetBufferSize * 2
                    )

                    val result = engine.wrap(
                        ByteBuffer.allocate(0),
                        output
                    )

                    if (result.status == SSLEngineResult.Status.OK ||
                        result.status == SSLEngineResult.Status.CLOSED
                    ) {
                        output.flip()
                        if (output.hasRemaining()) {
                            val bytes = ByteArray(output.remaining())
                            output.get(bytes)
                            transport.write(bytes)
                        }
                    }
                } finally {
                    writeMutex.unlock()
                }
            }
        } finally {
            transport.close(cause)
        }
    }


    companion object {
        fun createMutualTlsContext(
            keyStoreInput: InputStream,
            keyStorePassword: CharArray,
            caCertificateInput: InputStream
        ): SSLContext {
            val keyStore = KeyStore.getInstance("PKCS12")

            keyStoreInput.use { input ->
                keyStore.load(input, keyStorePassword)
            }

            require(keyStore.aliases().toList().any { alias ->
                keyStore.isKeyEntry(alias)
            }) {
                "PKCS#12 contains no private key entry"
            }

            val keyManagerFactory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
            )

            keyManagerFactory.init(keyStore, keyStorePassword)

            val caCertificate = caCertificateInput.use { input ->
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(input) as X509Certificate
            }

            caCertificate.checkValidity()

            require(caCertificate.basicConstraints >= 0) {
                "The trusted certificate is not a CA certificate"
            }

            val trustStore = KeyStore.getInstance(
                KeyStore.getDefaultType()
            ).apply {
                load(null, null)
                setCertificateEntry("knot-root-ca", caCertificate)
            }

            val trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )

            trustManagerFactory.init(trustStore)

            return SSLContext.getInstance("TLS").apply {
                init(
                    keyManagerFactory.keyManagers,
                    trustManagerFactory.trustManagers,
                    null
                )
            }
        }
    }
}