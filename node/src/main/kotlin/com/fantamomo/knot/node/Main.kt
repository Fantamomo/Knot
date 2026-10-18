package com.fantamomo.knot.node

import kotlinx.coroutines.runBlocking

fun main(array: Array<String>) = runBlocking {
    Node.start(array)
}