package com.fantamomo.knot.shared.net.packets

import io.github.classgraph.ClassGraph
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertTrue

class PacketRegistryTest {
    @Test
    fun `all packet subclasses are registered`() {
        val packetSubclasses = ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .scan()
            .use { result ->
                result.getSubclasses(Packet::class.java)
                    .filter { !it.isAbstract && !it.hasAnnotation(PacketLaterRegistered::class.java) }
                    .map { it.loadClass() }
                    .toSet()
            }

        @Suppress("UNCHECKED_CAST")
        val unregistered = packetSubclasses.filter { !PacketRegistry.isRegistered(it.kotlin as KClass<out Packet>) }
        assertTrue(
            unregistered.isEmpty(),
            buildString {
                appendLine("Unregistered packet subclasses: ")
                unregistered.forEach {
                    appendLine("- ${it.simpleName}")
                }
            }
        )
    }
}