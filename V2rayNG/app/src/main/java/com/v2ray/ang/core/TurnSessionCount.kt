package com.v2ray.ang.core

/** Local TURN preference, independent of DNS parallelism and subscription policy. */
object TurnSessionCount {
    const val DEFAULT = 10
    val choices = listOf(1, 2, 4, 10, 16)
    fun normalize(value: Int): Int = value.takeIf { it in choices } ?: DEFAULT
}
