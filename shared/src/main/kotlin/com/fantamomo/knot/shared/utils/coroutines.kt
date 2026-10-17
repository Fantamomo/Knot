package com.fantamomo.knot.shared.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

@JvmName("switchVoid")
suspend fun CoroutineScope.switch(block: suspend () -> Unit) {
    if (currentCoroutineContext() == coroutineContext) {
        block()
        return
    }
    val job = launch { block() }
    job.join()
}

suspend fun <T> CoroutineScope.switch(block: suspend () -> T): T {
    if (currentCoroutineContext() == coroutineContext) {
        return block()
    }
    val job = async { block() }
    return job.await()
}