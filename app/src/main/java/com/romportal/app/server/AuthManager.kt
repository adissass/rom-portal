package com.romportal.app.server

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

internal data class AuthConfig(
    val sessionTtlMs: Long = 30 * 60 * 1000,
    val inactivityTimeoutMs: Long = 10 * 60 * 1000,
    val baseBackoffMs: Long = 1000,
    val maxBackoffMs: Long = 30_000
)

internal sealed class LoginResult {
    data class Success(val token: String) : LoginResult()
    data class InvalidPin(val retryAfterMs: Long) : LoginResult()
    data class Blocked(val retryAfterMs: Long) : LoginResult()
}

internal class AuthManager(
    private val config: AuthConfig = AuthConfig(),
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private data class Session(
        var expiresAtMs: Long,
        var lastSeenAtMs: Long
    )

    private data class FailedAttempts(
        var failureCount: Int,
        var blockedUntilMs: Long
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val failedAttemptsByKey = ConcurrentHashMap<String, FailedAttempts>()

    fun clear() {
        sessions.clear()
        failedAttemptsByKey.clear()
    }

    fun activeSessionCount(): Int = sessions.size

    fun login(pinInput: String, expectedPin: String, attemptKey: String, nowMs: Long): LoginResult {
        val attemptState = failedAttemptsByKey[attemptKey]
        if (attemptState != null && nowMs < attemptState.blockedUntilMs) {
            return LoginResult.Blocked(attemptState.blockedUntilMs - nowMs)
        }

        if (pinInput == expectedPin) {
            failedAttemptsByKey.remove(attemptKey)
            val token = generateSessionToken()
            sessions[token] = Session(
                expiresAtMs = nowMs + config.sessionTtlMs,
                lastSeenAtMs = nowMs
            )
            return LoginResult.Success(token)
        }

        val updated = failedAttemptsByKey.compute(attemptKey) { _, old ->
            val nextFailures = (old?.failureCount ?: 0) + 1
            val delayMs = computeBackoffMs(nextFailures)
            FailedAttempts(nextFailures, nowMs + delayMs)
        }!!

        return LoginResult.InvalidPin(updated.blockedUntilMs - nowMs)
    }

    fun isSessionValid(token: String?, nowMs: Long): Boolean {
        if (token.isNullOrBlank()) return false

        val session = sessions[token] ?: return false
        val timedOutByInactivity = nowMs - session.lastSeenAtMs > config.inactivityTimeoutMs
        val expired = nowMs > session.expiresAtMs

        if (timedOutByInactivity || expired) {
            sessions.remove(token)
            return false
        }

        session.lastSeenAtMs = nowMs
        return true
    }

    private fun computeBackoffMs(failureCount: Int): Long {
        val shift = min(failureCount - 1, 10)
        val multiplier = 1L shl shift
        return min(config.maxBackoffMs, config.baseBackoffMs * multiplier)
    }

    private fun generateSessionToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
