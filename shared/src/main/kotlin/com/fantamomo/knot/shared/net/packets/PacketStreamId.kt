package com.fantamomo.knot.shared.net.packets

@ConsistentCopyVisibility
data class PacketStreamId private constructor(val name: String) {

    companion object {
        val INTERNAL = PacketStreamId("internal")
        val COMMANDS = PacketStreamId("commands")
        val EVENTS = PacketStreamId("events")
        val DATA_FILE = PacketStreamId("data/file")
        val DATA_RAW_TCP = PacketStreamId("data/raw/tcp")

        val OTHER = PacketStreamId("other")
    }
}