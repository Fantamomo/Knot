package com.fantamomo.knot.shared.net.packets

import kotlin.reflect.KClass

object PacketRegistry {
    private val byId: MutableMap<String, PacketCodec<out Packet>> = mutableMapOf()
    private val byClass: MutableMap<KClass<out Packet>, PacketCodec<out Packet>> = mutableMapOf()

    fun register(codec: PacketCodec<out Packet>) {
        val id = codec.id
        val packetClass = codec.packetClass

        if (id.isEmpty()) throw IllegalArgumentException("Packet id cannot be empty")
        if (id.length > 255) throw IllegalArgumentException("Packet id cannot be longer than 255 characters: $id")
        if (packetClass == Packet::class) throw IllegalArgumentException("Packet class cannot be Packet::class")

        if (byId.containsKey(id)) throw IllegalArgumentException("Packet id already registered: $id")
        if (byClass.containsKey(packetClass)) throw IllegalArgumentException("Packet class already registered: $packetClass")

        byId[id] = codec
        byClass[packetClass] = codec
    }

    fun getCodec(id: String): PacketCodec<out Packet> = byId[id] ?: throw IllegalArgumentException("No packet codec registered for id: $id")

    @Suppress("UNCHECKED_CAST")
    fun <P : Packet> getCodec(clazz: KClass<P>): PacketCodec<P> = byClass[clazz] as? PacketCodec<P> ?: throw IllegalArgumentException("No packet codec registered for class: $clazz")

    inline fun <reified P : Packet> getCodec(): PacketCodec<P> = getCodec(P::class)
}