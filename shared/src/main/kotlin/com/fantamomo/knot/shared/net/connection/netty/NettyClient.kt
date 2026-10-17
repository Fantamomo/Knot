package com.fantamomo.knot.shared.net.connection.netty

import com.fantamomo.knot.shared.net.connection.Client
import com.fantamomo.knot.shared.net.transport.Transport
import com.fantamomo.knot.shared.net.transport.impl.IncomingBytes
import com.fantamomo.knot.shared.net.transport.impl.NettyTransport
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup

class NettyClient(channel: Channel, group: EventLoopGroup, incomingBytes: IncomingBytes) : Client {
    override val transport: Transport = NettyTransport(channel, group, incomingBytes)

    override suspend fun close(cause: Throwable?) {
        transport.close(cause)
    }
}