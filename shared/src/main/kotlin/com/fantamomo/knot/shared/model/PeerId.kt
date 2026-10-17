package com.fantamomo.knot.shared.model

import java.security.MessageDigest
import kotlin.random.Random

data class PeerId(val id: ByteArray) {

    init {
        require(id.size == SIZE) { "PeerId must be $SIZE bytes long" }
    }

    val isBroadcast: Boolean = id.all { it == 0.toByte() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PeerId

        return id.contentEquals(other.id)
    }

    override fun hashCode() = id.contentHashCode()

    override fun toString() = id.toHexString()

    companion object {
        const val SIZE: Int = 16

        fun broadcast(): PeerId = PeerId(ByteArray(SIZE))

        fun random(): PeerId = PeerId(ByteArray(SIZE).also { Random.nextBytes(it) })

        fun fromHexString(str: String) = PeerId(str.hexToByteArray())

        fun fromString(str: String): PeerId {
            val instance = MessageDigest.getInstance("SHA-256")
            val digest = instance.digest(str.toByteArray())
            val data = ByteArray(SIZE)
            System.arraycopy(digest, 0, data, 0, SIZE)
            return PeerId(data)
        }
    }
}