package com.romportal.app.service

internal data class IdleSchedule(
    val warningDelayMs: Long?,
    val stopDelayMs: Long?
)

internal class IdleTimeoutPolicy(
    private val idleTimeoutMs: Long,
    private val warningLeadMs: Long
) {
    private var lastActivityAtMs: Long = 0
    private var warningShown: Boolean = false
    private var activeTransferCount: Int = 0

    fun onServerStarted(nowMs: Long): IdleSchedule {
        lastActivityAtMs = nowMs
        warningShown = false
        activeTransferCount = 0
        return scheduleFrom(nowMs)
    }

    fun onActivity(nowMs: Long): IdleSchedule {
        lastActivityAtMs = nowMs
        warningShown = false
        return scheduleFrom(nowMs)
    }

    fun onTransferStarted(nowMs: Long): IdleSchedule {
        activeTransferCount += 1
        lastActivityAtMs = nowMs
        warningShown = false
        return IdleSchedule(null, null)
    }

    fun onTransferFinished(nowMs: Long): IdleSchedule {
        if (activeTransferCount > 0) {
            activeTransferCount -= 1
        }
        if (activeTransferCount > 0) {
            return IdleSchedule(null, null)
        }
        lastActivityAtMs = nowMs
        warningShown = false
        return scheduleFrom(nowMs)
    }

    fun warningDue(nowMs: Long): Boolean {
        if (activeTransferCount > 0 || warningShown) return false
        val warningAt = idleTimeoutMs - warningLeadMs
        if (warningAt <= 0) return false
        val due = nowMs - lastActivityAtMs >= warningAt
        if (due) {
            warningShown = true
        }
        return due
    }

    fun stopDue(nowMs: Long): Boolean {
        if (activeTransferCount > 0) return false
        return nowMs - lastActivityAtMs >= idleTimeoutMs
    }

    fun warningRemainingMs(nowMs: Long): Long {
        val warningAt = (idleTimeoutMs - warningLeadMs).coerceAtLeast(0)
        return (warningAt - (nowMs - lastActivityAtMs)).coerceAtLeast(0)
    }

    fun stopRemainingMs(nowMs: Long): Long {
        return (idleTimeoutMs - (nowMs - lastActivityAtMs)).coerceAtLeast(0)
    }

    private fun scheduleFrom(nowMs: Long): IdleSchedule {
        if (activeTransferCount > 0) return IdleSchedule(null, null)
        val warningDelay = (idleTimeoutMs - warningLeadMs).coerceAtLeast(0)
        val stopDelay = idleTimeoutMs.coerceAtLeast(0)
        return IdleSchedule(
            warningDelayMs = warningDelay - (nowMs - lastActivityAtMs),
            stopDelayMs = stopDelay - (nowMs - lastActivityAtMs)
        ).let {
            IdleSchedule(
                warningDelayMs = it.warningDelayMs?.coerceAtLeast(0),
                stopDelayMs = it.stopDelayMs?.coerceAtLeast(0)
            )
        }
    }
}

