package com.fantamomo.knot.shared.utils

import io.netty.util.concurrent.Future
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Future<T>.awaitResult(): T {
    // fast path
    if (isSuccess) return getNow()
    else if (isDone) throw cause() ?: IOException("Netty operation failed")

    // slow path: suspend
    return suspendCancellableCoroutine { continuation ->
        addListener { future ->
            if (future.isSuccess) {
                @Suppress("UNCHECKED_CAST")
                continuation.resume(future.getNow() as T)
            } else {
                continuation.resumeWithException(
                    future.cause() ?: IOException("Netty operation failed")
                )
            }
        }
    }
}
