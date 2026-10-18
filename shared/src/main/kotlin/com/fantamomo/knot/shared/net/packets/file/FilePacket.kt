package com.fantamomo.knot.shared.net.packets.file

import com.fantamomo.knot.shared.net.packets.Packet
import com.fantamomo.knot.shared.net.packets.PacketCodec
import com.fantamomo.knot.shared.net.packets.PacketRegistry
import org.slf4j.LoggerFactory
import kotlin.reflect.full.companionObjectInstance

sealed class FilePacket : Packet() {
    companion object {
        private val logger = LoggerFactory.getLogger(FilePacket::class.java)

        fun register() {
            val subclasses = FilePacket::class.sealedSubclasses
            for (subclass in subclasses) {
                if (!subclass.isFinal) continue
                val objectInstance = subclass.objectInstance
                if (objectInstance != null) {
                    if (objectInstance is PacketCodec<*>) {
                        PacketRegistry.register(objectInstance)
                    } else {
                        logger.warn("Found FilePacket subclass that is an object instance, but does not implement PacketCodec")
                    }
                    continue
                }
                val companion = subclass.companionObjectInstance
                if (companion != null) {
                    if (companion is PacketCodec<*>) {
                        PacketRegistry.register(companion)
                    } else {
                        logger.warn("Found FilePacket subclass that has a companion object, which does not implement PacketCodec")
                    }
                    continue
                }
                logger.warn("Found FilePacket subclass but could not find an PacketCodec")
            }
        }
    }
}