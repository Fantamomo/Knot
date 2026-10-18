package com.fantamomo.knot.node

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class NodeConfig(
    val configDir: Path,
    val host: String = "0.0.0.0",
    val port: Int = 5001,
    val caFileName: String = "ca.crt",
    val keyStoreFileName: String = "peer.p12",
    val keyStorePasswordFileName: String = "peer.p12.password",
    val peerIdFileName: String = "peer.id",
    val knownPeersFileName: String = "known.peers",
    val bootstrapNode: String? = null,
    val reconnect: ReconnectConfig = ReconnectConfig()
) {
    data class ReconnectConfig(
        val enabled: Boolean = true,
        val initialDelay: Duration = 1.seconds,
        val maxDelay: Duration = 60.seconds,
        val multiplier: Double = 2.0,
        val jitter: Double = 0.2,
        val connectTimeout: Duration = 10.seconds,
        val checkInterval: Duration = 1.seconds,
        val maxAttempts: Int? = null
    ) {
        init {
            require(multiplier >= 1.0) { "reconnect.multiplier must be >= 1.0" }
            require(jitter in 0.0..1.0) { "reconnect.jitter must be between 0.0 and 1.0" }
            require(initialDelay.isPositive() && maxDelay >= initialDelay) { "Invalid reconnect delays" }
        }
    }

    companion object {
        fun load(args: Array<String>): NodeConfig {
            val options = args.filter { it.startsWith("--") }
            val pathString = args.filterNot { it.startsWith("--") }.joinToString(" ")
            if (pathString.isBlank()) throw IllegalArgumentException("Expected the config directory as argument")

            val dir = try {
                Path.of(pathString)
            } catch (e: InvalidPathException) {
                throw IllegalArgumentException("Invalid path: $pathString", e)
            }

            val values = HashMap<String, String>()
            val propsFile = dir.resolve("node.properties")
            if (propsFile.exists()) {
                val props = Properties().apply { propsFile.inputStream().use { load(it) } }
                for (name in props.stringPropertyNames()) values[name] = props.getProperty(name).trim()
            }
            for (option in options) {
                val (key, value) = option.removePrefix("--").split("=", limit = 2).let {
                    if (it.size != 2) throw IllegalArgumentException("Option must look like --key=value: $option")
                    it[0] to it[1]
                }
                values[key] = value.trim()
            }

            val d = NodeConfig(dir)
            val rd = d.reconnect
            return NodeConfig(
                configDir = dir,
                host = values["host"] ?: d.host,
                port = values["port"]?.toIntOrNull() ?: d.port,
                caFileName = values["file.ca"] ?: d.caFileName,
                keyStoreFileName = values["file.keystore"] ?: d.keyStoreFileName,
                keyStorePasswordFileName = values["file.keystore.password"] ?: d.keyStorePasswordFileName,
                peerIdFileName = values["file.peerId"] ?: d.peerIdFileName,
                knownPeersFileName = values["file.knownPeers"] ?: d.knownPeersFileName,
                bootstrapNode = values["bootstrap.node"] ?: d.bootstrapNode,
                reconnect = ReconnectConfig(
                    enabled = values["reconnect.enabled"]?.toBooleanStrictOrNull() ?: rd.enabled,
                    initialDelay = values["reconnect.initialDelay"]?.let(Duration.Companion::parse) ?: rd.initialDelay,
                    maxDelay = values["reconnect.maxDelay"]?.let(Duration.Companion::parse) ?: rd.maxDelay,
                    multiplier = values["reconnect.multiplier"]?.toDoubleOrNull() ?: rd.multiplier,
                    jitter = values["reconnect.jitter"]?.toDoubleOrNull() ?: rd.jitter,
                    connectTimeout = values["reconnect.connectTimeout"]?.let(Duration.Companion::parse) ?: rd.connectTimeout,
                    checkInterval = values["reconnect.checkInterval"]?.let(Duration.Companion::parse) ?: rd.checkInterval,
                    maxAttempts = values["reconnect.maxAttempts"]?.toIntOrNull() ?: rd.maxAttempts
                )
            )
        }
    }
}