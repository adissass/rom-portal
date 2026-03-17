package com.romportal.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleTimeoutPolicyTest {
    @Test
    fun warningAndStop_fireOnExpectedTimeline_whenIdle() {
        val policy = IdleTimeoutPolicy(idleTimeoutMs = 120_000, warningLeadMs = 30_000)
        policy.onServerStarted(nowMs = 1_000)

        assertFalse(policy.warningDue(nowMs = 90_000))
        assertTrue(policy.warningDue(nowMs = 91_000))
        assertFalse(policy.warningDue(nowMs = 100_000)) // warning once per cycle

        assertFalse(policy.stopDue(nowMs = 120_999))
        assertTrue(policy.stopDue(nowMs = 121_000))
    }

    @Test
    fun keepAliveActivity_resetsWindow() {
        val policy = IdleTimeoutPolicy(idleTimeoutMs = 60_000, warningLeadMs = 10_000)
        policy.onServerStarted(nowMs = 0)
        assertTrue(policy.warningDue(nowMs = 50_000))

        val schedule = policy.onActivity(nowMs = 55_000)
        assertEquals(50_000L, schedule.warningDelayMs)
        assertEquals(60_000L, schedule.stopDelayMs)
        assertFalse(policy.warningDue(nowMs = 104_000))
        assertTrue(policy.warningDue(nowMs = 105_000))
    }

    @Test
    fun activeTransfer_suppressesWarningAndStop_untilTransferFinishes() {
        val policy = IdleTimeoutPolicy(idleTimeoutMs = 30_000, warningLeadMs = 10_000)
        policy.onServerStarted(nowMs = 0)
        policy.onTransferStarted(nowMs = 5_000)

        assertFalse(policy.warningDue(nowMs = 100_000))
        assertFalse(policy.stopDue(nowMs = 100_000))

        val scheduleAfterFinish = policy.onTransferFinished(nowMs = 110_000)
        assertEquals(20_000L, scheduleAfterFinish.warningDelayMs)
        assertEquals(30_000L, scheduleAfterFinish.stopDelayMs)
        assertFalse(policy.warningDue(nowMs = 129_999))
        assertTrue(policy.warningDue(nowMs = 130_000))
    }

    @Test
    fun overlappingTransfers_requireAllToFinish_beforeResumingTimers() {
        val policy = IdleTimeoutPolicy(idleTimeoutMs = 40_000, warningLeadMs = 10_000)
        policy.onServerStarted(nowMs = 0)
        policy.onTransferStarted(nowMs = 1_000)
        policy.onTransferStarted(nowMs = 2_000)

        val firstFinish = policy.onTransferFinished(nowMs = 5_000)
        assertEquals(null as Long?, firstFinish.warningDelayMs)
        assertEquals(null as Long?, firstFinish.stopDelayMs)
        assertFalse(policy.warningDue(nowMs = 200_000))
        assertFalse(policy.stopDue(nowMs = 200_000))

        val secondFinish = policy.onTransferFinished(nowMs = 210_000)
        assertEquals(30_000L, secondFinish.warningDelayMs)
        assertEquals(40_000L, secondFinish.stopDelayMs)
        assertFalse(policy.warningDue(nowMs = 239_999))
        assertTrue(policy.warningDue(nowMs = 240_000))
    }
}
