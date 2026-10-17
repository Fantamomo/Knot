package com.fantamomo.knot.shared.net.util

import com.fantamomo.knot.shared.net.packets.PacketEnvelop
import kotlin.uuid.Uuid

interface PacketDeduplicator {

    fun isDuplicateAndMark(envelop: PacketEnvelop) = isDuplicateAndMark(envelop.id)

    /**
     * Adds the packet to the deduplicator and returns true if it is a duplicate, false otherwise
     *
     * Note this method must be thread-safe and efficient.
     * (In one of the first attempts, this method was responsible for around 50% of the CPU usage)
     *
     * @return true if the packet is a duplicate, false otherwise
     */
    fun isDuplicateAndMark(id: Uuid): Boolean
}

// the first attempts where programmed by me, but this one is from AI (and also it is much more efficient)
@Suppress("SpellCheckingInspection") // yeah, some of the bit operators (e.g. ushr)
class DefaultPacketDeduplicator(
    windowSeconds: Long = 60,
    shards: Int = 32,
    initialCapacityPerShard: Int = 256,
) : PacketDeduplicator {

    init {
        require(windowSeconds > 0) { "windowSeconds must be > 0" }
        require(shards > 0) { "shards must be > 0" }
        require(initialCapacityPerShard > 0) { "initialCapacityPerShard must be > 0" }
    }

    private val ttlNanos = windowSeconds * 1_000_000_000L

    private val origin = System.nanoTime() - 1

    private val shardMask = nextPow2(shards) - 1
    private val minCapacity = nextPow2(maxOf(initialCapacityPerShard, 16))
    private val table = Array(shardMask + 1) { Shard(minCapacity) }

    override fun isDuplicateAndMark(id: Uuid): Boolean = id.toLongs { hi, lo ->
        val now = System.nanoTime() - origin
        val h = mix(hi, lo)
        table[(h ushr 32).toInt() and shardMask]
            .checkAndMark(h, hi, lo, now, now + ttlNanos)
    }

    private class Shard(private val minCapacity: Int) {
        private var capacity = minCapacity
        private var mask = capacity - 1
        private var hi = LongArray(capacity)
        private var lo = LongArray(capacity)
        private var exp = LongArray(capacity)
        private var used = 0

        @Synchronized
        fun checkAndMark(h: Long, a: Long, b: Long, now: Long, expiry: Long): Boolean {
            var i = h.toInt() and mask
            var reuse = -1
            while (true) {
                val e = exp[i]
                if (e == 0L) break
                if (e > now) {
                    if (hi[i] == a && lo[i] == b) return true
                } else if (reuse < 0) {
                    reuse = i
                }
                i = (i + 1) and mask
            }

            if (reuse >= 0) {
                hi[reuse] = a; lo[reuse] = b; exp[reuse] = expiry
                return false
            }

            if (used + 1 > capacity - (capacity ushr 2)) { // Load > 75 %
                rehash(now)
                i = h.toInt() and mask
                while (exp[i] != 0L) i = (i + 1) and mask
            }

            hi[i] = a; lo[i] = b; exp[i] = expiry
            used++
            return false
        }

        private fun rehash(now: Long) {
            val oldHi = hi
            val oldLo = lo
            val oldExp = exp

            var live = 0
            for (e in oldExp) if (e > now) live++

            val newCapacity = maxOf(minCapacity, nextPow2(live * 4))

            val onRehash = Debugger.onRehash
            if (onRehash !== null) {
                onRehash(capacity, newCapacity)
            }

            capacity = newCapacity
            mask = capacity - 1
            hi = LongArray(capacity)
            lo = LongArray(capacity)
            exp = LongArray(capacity)
            used = 0

            for (j in oldExp.indices) {
                val e = oldExp[j]
                if (e <= now) continue
                var k = mix(oldHi[j], oldLo[j]).toInt() and mask
                while (exp[k] != 0L) k = (k + 1) and mask
                hi[k] = oldHi[j]; lo[k] = oldLo[j]; exp[k] = e
                used++
            }
        }
    }

    private companion object {
        @Suppress("NOTHING_TO_INLINE")
        private inline fun mix(a: Long, b: Long): Long {
            var h = a xor (b * -0x61c8864680b583ebL)
            h = h xor (h ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL
            h = h xor (h ushr 33)
            return h
        }

        private fun nextPow2(n: Int): Int = if (n <= 1) 1 else 1 shl (32 - Integer.numberOfLeadingZeros(n - 1))
    }

    object Debugger {
        // debugger function, called in rehash with the old capacity and the new capacity
        var onRehash: ((old: Int, new: Int) -> Unit)? = null
    }
}