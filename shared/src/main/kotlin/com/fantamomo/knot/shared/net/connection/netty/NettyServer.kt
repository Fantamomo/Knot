package com.fantamomo.knot.shared.net.connection.netty

import com.fantamomo.knot.shared.net.connection.Client
import com.fantamomo.knot.shared.net.connection.Server
import com.fantamomo.knot.shared.net.transport.impl.IncomingBytes
import com.fantamomo.knot.shared.utils.awaitResult
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import kotlinx.coroutines.channels.getOrElse
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel as CoroutineChannel

class NettyServer(
    private val host: String,
    private val port: Int,
) : Server {

    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()
    private val accepting = AtomicBoolean(false)

    private val pendingClients = CoroutineChannel<Client>(
        capacity = CoroutineChannel.UNLIMITED
    )

    @Volatile
    private var serverChannel: Channel? = null

    override suspend fun start() {
        check(accepting.compareAndSet(false, true)) {
            "Server is already started"
        }

        try {
            val bootstrap = ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(object : ChannelInitializer<SocketChannel>() {

                    override fun initChannel(ch: SocketChannel) {
                        val incoming = IncomingBytes(MAX_BUFFERED_BYTES)

                        ch.pipeline().addLast(
                            "raw-bytes",
                            object : ChannelInboundHandlerAdapter() {

                                override fun channelRead(
                                    ctx: ChannelHandlerContext,
                                    msg: Any
                                ) {
                                    if (msg !is ByteBuf) {
                                        ctx.fireChannelRead(msg)
                                        return
                                    }

                                    try {
                                        val bytes = ByteArray(msg.readableBytes())
                                        msg.readBytes(bytes)

                                        if (!incoming.offer(bytes)) {
                                            ctx.close()
                                        }
                                    } finally {
                                        msg.release()
                                    }
                                }

                                override fun channelInactive(
                                    ctx: ChannelHandlerContext
                                ) {
                                    incoming.close(null)
                                    ctx.fireChannelInactive()
                                }

                                override fun exceptionCaught(
                                    ctx: ChannelHandlerContext,
                                    cause: Throwable
                                ) {
                                    incoming.close(cause)
                                    ctx.close()
                                }
                            }
                        )

                        val client = NettyClient(
                            ch,
                            workerGroup,
                            incoming
                        )

                        if (!pendingClients.trySend(client).isSuccess) {
                            incoming.close(null)
                            ch.close()
                        }
                    }
                })

            val bindFuture = bootstrap.bind(host, port)
            bindFuture.awaitResult()

            serverChannel = bindFuture.channel()
        } catch (t: Throwable) {
            accepting.set(false)
            pendingClients.close(t)
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
            throw t
        }
    }

    override suspend fun accept(): Client {
        check(accepting.get()) { "Server is not started" }

        return pendingClients.receiveCatching().getOrElse { cause ->
            throw cause ?: IllegalStateException("Server has been shut down")
        }
    }

    override suspend fun shutdown() {
        if (!accepting.compareAndSet(true, false)) {
            return
        }

        try {
            serverChannel?.close()?.awaitResult()
        } finally {
            pendingClients.close()

            bossGroup.shutdownGracefully().awaitResult()
            workerGroup.shutdownGracefully().awaitResult()
        }
    }

    private companion object {
        const val MAX_BUFFERED_BYTES = 4 * 1024 * 1024
    }
}