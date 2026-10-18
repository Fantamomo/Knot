package com.fantamomo.knot.shared.manager

import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.Network
import com.fantamomo.knot.shared.net.packets.file.*
import com.fantamomo.knot.shared.net.util.incomingPackets
import kotlinx.coroutines.*
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class FileManager(val network: Network, val outputDir: Path) {
    private val incomingDir = outputDir.resolve("incoming")

    private val fileUploadRequestResponsePackets: MutableMap<Uuid, CompletableDeferred<FileUploadRequestResponsePacket>> = mutableMapOf()

    private val incomingFiles: MutableMap<Uuid, IncomingFile> = mutableMapOf()

    private inner class IncomingFile(
        val requestId: Uuid,
        val fileName: String,
        val id: Uuid,
        val source: PeerId,
        val expectedSize: ULong,
        var chunks: Int = 0,
        var receivedSize: ULong = 0uL,
    ) {
        val temporaryFile: Path = incomingDir.resolve("$id.tmp")

        init {
            if (incomingDir.notExists()) incomingDir.createDirectories()
            temporaryFile.toFile().deleteOnExit()
        }

        val sink = temporaryFile.outputStream().asSink().buffered()

        suspend fun write(data: ByteArray): Unit = withContext(Dispatchers.IO) {
            sink.write(data)
            receivedSize += data.size.toULong()
            chunks++
        }

        suspend fun finish(): Unit = withContext(Dispatchers.IO) {
            sink.close()
            val dir = outputDir.resolve(id.toString())
            dir.createDirectories()
            temporaryFile.moveTo(dir.resolve(fileName))
        }
    }

    suspend fun start() {
        network.incomingPackets<FilePacket>().collect { incomingPacket ->
            when (val packet = incomingPacket.packet) {
                is FileUploadRequestPacket -> handleFileUploadRequest(packet, incomingPacket.source)
                is FileUploadRequestResponsePacket -> handleFileUploadRequestResponse(packet, incomingPacket.source)
                is FinishFileUploadPacket -> handleFinishFileUpload(packet, incomingPacket.source)
                is UploadFileChunkPacket -> handleUploadFileChunk(packet, incomingPacket.source)
            }
        }
    }

    private fun handleFileUploadRequest(packet: FileUploadRequestPacket, source: PeerId) {
        network.scope.launch {
            val uploadId = Uuid.random()
            incomingFiles[uploadId] = IncomingFile(packet.requestId, packet.fileName, uploadId, source, packet.expectedFileSize)
            val responsePacket = FileUploadRequestResponsePacket(packet.requestId, true, null, uploadId)
            network.sendPacket(responsePacket, source)
        }
    }

    private fun handleFileUploadRequestResponse(packet: FileUploadRequestResponsePacket, source: PeerId) {
        val deferred = fileUploadRequestResponsePackets[packet.requestId] ?: return
        deferred.complete(packet)
    }

    private suspend fun handleFinishFileUpload(packet: FinishFileUploadPacket, source: PeerId) {
        val incomingFile = incomingFiles.remove(packet.fileId)
        if (incomingFile == null) {
            logger.warn("Received finish file upload for unknown file id ${packet.fileId}")
            return
        }
        if (incomingFile.source != source) {
            logger.warn("Received finish file upload from wrong source $source for file id ${packet.fileId}")
            return
        }
        incomingFile.finish()
    }

    private suspend fun handleUploadFileChunk(packet: UploadFileChunkPacket, source: PeerId) {
        val incomingFile = incomingFiles[packet.uploadId]
        if (incomingFile == null) {
            logger.warn("Received upload chunk for unknown upload id ${packet.uploadId}")
            return
        }
        if (incomingFile.source != source) {
            logger.warn("Received upload chunk from wrong source $source for upload id ${packet.uploadId}")
            return
        }
        incomingFile.write(packet.chunk)
    }

    suspend fun uploadFile(path: Path, target: PeerId): Unit = withContext(Dispatchers.IO) {
        require(path.exists()) { "File does not exist" }
        require(!path.isDirectory()) { "Path is a directory" }
        require(path.isRegularFile()) { "Path is not a regular file" }

        val source = try {
            path.inputStream().asSource().buffered()
        } catch (e: Exception) {
            throw IOException("Failed to open file $path", e)
        }

        val requestId = Uuid.random()
        val response = CompletableDeferred<FileUploadRequestResponsePacket>()
        if (fileUploadRequestResponsePackets.putIfAbsent(requestId, response) != null) {
            // this is SO unlucky
            throw IllegalStateException("Request ID already in use")
        }

        val fileUploadRequestPacket = FileUploadRequestPacket(requestId,path.name, Files.size(path).toULong())
        network.sendPacket(fileUploadRequestPacket, target)

        val responsePacket = withTimeoutOrNull(5.seconds) {
            response.await()
        }
        fileUploadRequestResponsePackets.remove(requestId)
        if (responsePacket == null) {
            throw IOException("Peer did not respond to file upload request")
        }
        if (!responsePacket.success) {
            throw IOException("Peer rejected file upload request, reason: ${responsePacket.reason}")
        }
        val uploadId = responsePacket.uploadId ?: throw IllegalStateException("Upload ID not provided") // unreachable

        val chunk = ByteArray(1024)
        var chunks = 0
        var size = 0L

        while (true) {
            val read = source.readAtMostTo(chunk)
            if (read == -1) {
                val packet = FinishFileUploadPacket(uploadId, chunks, size)
                network.sendPacket(packet, target)
                break
            }

            // we need to use copy, because we cannot guarantee that the chunk is directly written to connection
            // so copying the chunk is necessary
            val uploadedBytes = chunk.copyOf(read)
            val chunkPacket = UploadFileChunkPacket(uploadId, chunks, uploadedBytes)

            network.sendPacket(chunkPacket, target)

            chunks++
            size += read
        }
    }

    suspend fun uploadDir(path: Path) {
        require(path.exists()) { "Directory does not exist" }
        require(path.isDirectory()) { "Path is not a directory" }

        TODO("Not yet implemented")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FileManager::class.java)
    }
}