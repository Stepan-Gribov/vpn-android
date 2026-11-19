package com.v2ray.ang.simple

import org.junit.Assert.*
import org.junit.Test

class BackgroundProbeScheduleTest {
    @Test fun intervalChangeAppliesToRunningTimer() {
        val schedule = BackgroundProbeSchedule(0)
        assertFalse(schedule.isDue(20_000, 300_000))
        assertTrue(schedule.isDue(20_000, 15_000))
        schedule.completed(40_000)
        assertFalse(schedule.isDue(54_999, 15_000))
        assertTrue(schedule.isDue(55_000, 15_000))
        assertFalse(schedule.isDue(55_000, 120_000))
    }
}
