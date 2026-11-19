package com.v2ray.ang.simple

/** Slow preparation runs outside this lock. Cancelled startup must not publish over a manual choice. */
internal object StagedPublication {
    private val lock = Any()
    fun <T> publish(allowed: () -> Boolean, write: () -> T): T = synchronized(lock) {
        check(allowed()) { "Подготовка подключения отменена." }
        write()
    }
}
