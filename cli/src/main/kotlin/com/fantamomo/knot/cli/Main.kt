package com.fantamomo.knot.cli

import com.fantamomo.knot.shared.manager.FileManager
import com.fantamomo.knot.shared.manager.TunnelManager
import com.fantamomo.knot.shared.model.PeerId
import com.fantamomo.knot.shared.net.Network
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readLine
import kotlin.io.path.Path
import kotlin.system.exitProcess

// this is just a simple implementation,
// it will be replaced with a more complex one later
// future me: have fun TODO
fun main(args: Array<String>): Unit = runBlocking {
    val parser = ArgParser("Knot CLI")

    val host = parser.option(ArgType.String, "host", null, "the host to connect to").required()
    val port = parser.option(ArgType.Int, "port", "p", "the port to connect to").required()
    val caFile = parser.option(ArgType.String, "ca", "ca", "the cert of the CA").required()
    val keystoreFile = parser.option(ArgType.String, "key-store-file", "key", "the .p12 file to use").required()
    val p12Password =
        parser.option(ArgType.String, "key-store-password", "pass", "the password of the .p12 file").required()
    val localPeerId = parser.option(ArgType.String, "local-peer-id", "id", "the local peer id").required()
    val outputDir = parser.option(ArgType.String, "output-dir", "o", "the output directory of received files").default("./files")

    parser.parse(args)

    val network = Network(
        scope = this,
        host = null,
        port = null,
        localPeerId = PeerId.fromHexString(localPeerId.value),
        keyStoreFile = Path(keystoreFile.value),
        keyStorePassword = p12Password.value.toCharArray(),
        caCertificateInput = Path(caFile.value),
    )

    network.start()

    println("Connecting to ${host.value}:${port.value}")
    network.connectTo(host.value, port.value)
    println("Connected")

    val fileManager = FileManager(network, Path(outputDir.value))
    launch { fileManager.start() }

    val tunnelManager = TunnelManager(network)
    launch { tunnelManager.start() }

    val input = System.`in`.asSource().buffered()

    fun stop(): Nothing {
        println("Stopping...")
        cancel()
        exitProcess(0)
    }

    println("Type 'help' for a list of commands")

    while (isActive) {
        print("> ")
        val line = input.readLine() ?: stop()
        if (line.isBlank()) continue
        try {
            val args = line.split(" ")
            if (args.isEmpty()) continue
            when (val command = args.first()) {
                "stop", "exit" -> stop()
                "connect" -> {
                    if (args.size < 3) {
                        println("Error: missing host and port")
                        continue
                    }
                    val host = args[1]
                    val port = args[2].toInt()
                    println("Connecting to $host:$port...")
                    network.connectTo(host, port)
                    println("Connected to $host:$port")
                }
                "upload" -> {
                    if (args.size < 3) {
                        println("Error: missing target peer and file path")
                        continue
                    }
                    val target = PeerId.fromHexString(args[1])
                    val filePath = Path(args.drop(2).joinToString(" "))
                    println("Uploading file ${filePath.toAbsolutePath()} to $target...")
                    fileManager.uploadFile(filePath, target)
                    println("File uploaded")
                }
                "tunnel", "forward" -> {
                    if (args.size < 2) {
                        println("Error: missing sub command")
                    }
                    when (val subcommand = args[1]) {
                        "start", "open" -> {
                            if (args.size < 5) {
                                println("Error: missing target peer, destination host and port")
                            }
                            val target = PeerId.fromHexString(args[2])
                            val destinationHost = args[3]
                            val destinationPort = args[4].toInt()
                            val bindHost = args.getOrNull(5) ?: "0.0.0.0"
                            val bindPort = args.getOrNull(6)?.toInt() ?: 0
                            println("Opening tunnel on $target ($bindHost:$bindPort) with will forward to $destinationHost:$destinationPort")
                            tunnelManager.openTunnel(target, destinationHost, destinationPort, bindHost, bindPort)
                            println("Tunnel opened")
                        }
                        "stop" -> {
                            tunnelManager.stop()
                        }
                        else -> println("Unknown subcommand: $subcommand")
                    }
                }
                "ls", "list" -> {
                    val peers = network.peers
                    println("Directly connected peers:")
                    for ((id, connection) in peers) {
                        println("  $id: ${connection.client.transport.let { "${it.remoteAddr.hostAddress}:${it.remotePort}" }}")
                    }
                    println("Known peers:")
                    val knownPeers = network.router.knownPeers()
                    for (id in knownPeers) {
                        println("  $id")
                    }
                }
                "help" -> TODO("Not yet implemented")
                else -> println("Unknown command: $command")
            }
        } catch (e: Exception) {
            println("Error: $e")
        }
    }
}