package com.fantamomo.knot.shared.utils

import java.io.OutputStream

class LazyDelegatingOutputStream(val needClose: Boolean = true, initializer: () -> OutputStream) : OutputStream() {
    private val lazyInitializer = lazy(initializer)
    internal val delegate: OutputStream by lazyInitializer

    override fun write(b: Int) = delegate.write(b)

    override fun write(b: ByteArray) = delegate.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

    override fun flush() = delegate.flush()

    override fun close() {
        if (needClose || lazyInitializer.isInitialized()) {
            delegate.close()
        }
    }
}