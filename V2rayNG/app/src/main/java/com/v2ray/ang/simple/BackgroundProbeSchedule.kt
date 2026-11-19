package com.v2ray.ang.simple

/** Monotonic schedule; saved interval changes apply without restarting a connection. */
class BackgroundProbeSchedule(private var lastCompleted: Long) {
    fun isDue(now: Long, interval: Long): Boolean = now - lastCompleted >= interval
    fun completed(now: Long) { lastCompleted = now }
}
