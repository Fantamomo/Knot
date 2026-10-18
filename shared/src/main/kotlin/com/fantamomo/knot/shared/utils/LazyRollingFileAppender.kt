package com.fantamomo.knot.shared.utils

import ch.qos.logback.core.recovery.ResilientFileOutputStream
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.util.FileSize
import ch.qos.logback.core.util.FileUtil
import java.io.File

class LazyRollingFileAppender<E> : RollingFileAppender<E>() {

    private var cachedBufferSize: Long = DEFAULT_BUFFER_SIZE

    override fun openFile(@Suppress("LocalVariableName") file_name: String) {
        streamWriteLock.lock()
        try {
            val file = File(file_name)

            val append = append
            val bufferSize = cachedBufferSize

            val stream = LazyDelegatingOutputStream(false) {
                val result = FileUtil.createMissingParentDirectories(file)
                if (!result) {
                    addError("Failed to create parent directories for [" + file.getAbsolutePath() + "]")
                }
                val resilientFos = ResilientFileOutputStream(file, append, bufferSize)
                resilientFos.context = context
                resilientFos
            }
            setOutputStream(stream)
        } finally {
            streamWriteLock.unlock()
        }
    }

    override fun getOutputStream() = super.getOutputStream()?.let { (it as? LazyDelegatingOutputStream)?.delegate ?: it }

    // this helps us avoid reflection access
    override fun setBufferSize(bufferSize: FileSize?) {
        if (bufferSize == null) {
            // this methode will fail with a NullPointerException
            super.setBufferSize(null)
        } else {
            cachedBufferSize = bufferSize.size
            super.setBufferSize(bufferSize)
        }
    }
}