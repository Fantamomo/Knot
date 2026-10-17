package com.fantamomo.knot.shared.net.connection.netty

import com.fantamomo.knot.shared.net.connection.Client
import com.fantamomo.knot.shared.net.connection.ConnectionProvider
import com.fantamomo.knot.shared.net.transport.impl.IncomingBytes
import com.fantamomo.knot.shared.utils.awaitResult
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import javax.net.ssl.SSLParameters

object NettyConnectionProvider : ConnectionProvider {
    private const val MAX_BUFFERED_BYTES = 4 * 1024 * 1024

    override fun createServer(host: String, port: Int) = NettyServer(host, port)

    override suspend fun createClient(host: String, port: Int) = createClient(host, port, false)

    suspend fun createClient(host: String, port: Int, tls: Boolean): Client {
        val group = NioEventLoopGroup()
        val incoming = IncomingBytes(MAX_BUFFERED_BYTES)

        try {
            val bootstrap = Bootstrap()
                .group(group)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        if (tls) {
                            val context = SslContextBuilder.forClient().build()

                            val engine = context.newEngine(ch.alloc(), host, port)

                            val parameters: SSLParameters = engine.sslParameters
                            parameters.endpointIdentificationAlgorithm = "HTTPS"
                            engine.sslParameters = parameters

                            ch.pipeline().addLast("tls", SslHandler(engine))
                        }

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
                    }
                })

            val channelFuture = bootstrap.connect(host, port)
            channelFuture.awaitResult()
            val channel = channelFuture.channel()

            if (tls) {
                channel.pipeline()
                    .get(SslHandler::class.java)
                    .handshakeFuture()
                    .awaitResult()
            }

            return NettyClient(channel, group, incoming)
        } catch (t: Throwable) {
            incoming.close(t)
            group.shutdownGracefully()
            throw t
        }
    }
}