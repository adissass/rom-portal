package com.romportal.app.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthManagerTest {
    @Test
    fun validLogin_createsUsableSessionToken() {
        val manager = AuthManager()
        val now = 1_000L

        val result = manager.login(
            pinInput = "123456",
            expectedPin = "123456",
            attemptKey = "ip-1",
            nowMs = now
        )

        assertTrue(result is LoginResult.Success)
        val token = (result as LoginResult.Success).token
        assertTrue(token.length >= 40)
        assertTrue(manager.isSessionValid(token, now + 1_000))
    }

    @Test
    fun invalidLogin_isRateLimitedThenEventuallyUnblocked() {
        val manager = AuthManager(
            config = AuthConfig(baseBackoffMs = 1000, maxBackoffMs = 4000)
        )
        val now = 5_000L

        val invalid = manager.login("000000", "123456", "ip-2", now)
        assertTrue(invalid is LoginResult.InvalidPin)
        val retryAfter = (invalid as LoginResult.InvalidPin).retryAfterMs
        assertTrue(retryAfter >= 1000)

        val blocked = manager.login("123456", "123456", "ip-2", now + 100)
        assertTrue(blocked is LoginResult.Blocked)

        val success = manager.login("123456", "123456", "ip-2", now + retryAfter + 1)
        assertTrue(success is LoginResult.Success)
    }

    @Test
    fun sessionExpiresWhenTtlIsExceeded() {
        val manager = AuthManager(
            config = AuthConfig(
                sessionTtlMs = 1000,
                inactivityTimeoutMs = 10_000
            )
        )
        val now = 100L

        val result = manager.login("222222", "222222", "ip-3", now)
        assertTrue(result is LoginResult.Success)
        val token = (result as LoginResult.Success).token

        assertTrue(manager.isSessionValid(token, now + 500))
        assertFalse(manager.isSessionValid(token, now + 1_500))
    }

    @Test
    fun sessionExpiresWhenInactivityThresholdIsExceeded() {
        val manager = AuthManager(
            config = AuthConfig(
                sessionTtlMs = 60_000,
                inactivityTimeoutMs = 1_000
            )
        )
        val now = 5_000L

        val result = manager.login("333333", "333333", "ip-4", now)
        assertTrue(result is LoginResult.Success)
        val token = (result as LoginResult.Success).token

        assertTrue(manager.isSessionValid(token, now + 500))
        assertFalse(manager.isSessionValid(token, now + 1_600))
    }

    @Test
    fun successfulLoginClearsBackoffForAttemptKey() {
        val manager = AuthManager(config = AuthConfig(baseBackoffMs = 1000, maxBackoffMs = 4000))
        val now = 10_000L

        val firstFailure = manager.login("000000", "444444", "ip-5", now)
        assertTrue(firstFailure is LoginResult.InvalidPin)
        val retryAfter = (firstFailure as LoginResult.InvalidPin).retryAfterMs

        val success = manager.login("444444", "444444", "ip-5", now + retryAfter + 1)
        assertTrue(success is LoginResult.Success)

        // A new failure after successful auth should start backoff from the base delay.
        val nextFailure = manager.login("111111", "444444", "ip-5", now + retryAfter + 2)
        assertTrue(nextFailure is LoginResult.InvalidPin)
        assertTrue((nextFailure as LoginResult.InvalidPin).retryAfterMs in 1000..1100)
    }

    @Test
    fun repeatedInvalidLogins_increaseBackoffAndCapAtMax() {
        val manager = AuthManager(config = AuthConfig(baseBackoffMs = 500, maxBackoffMs = 2000))
        var now = 20_000L

        val first = manager.login("000000", "999999", "ip-6", now) as LoginResult.InvalidPin
        assertEquals(500, first.retryAfterMs)
        now += first.retryAfterMs + 1

        val second = manager.login("000000", "999999", "ip-6", now) as LoginResult.InvalidPin
        assertEquals(1000, second.retryAfterMs)
        now += second.retryAfterMs + 1

        val third = manager.login("000000", "999999", "ip-6", now) as LoginResult.InvalidPin
        assertEquals(2000, third.retryAfterMs)
        now += third.retryAfterMs + 1

        val fourth = manager.login("000000", "999999", "ip-6", now) as LoginResult.InvalidPin
        assertEquals(2000, fourth.retryAfterMs) // capped at maxBackoffMs
    }

    @Test
    fun blockedAttempt_rejectsCorrectPin_untilBlockExpires_thenResetsBackoff() {
        val manager = AuthManager(config = AuthConfig(baseBackoffMs = 1000, maxBackoffMs = 4000))
        var now = 30_000L

        val firstFailure = manager.login("000000", "555555", "ip-7", now) as LoginResult.InvalidPin
        now += firstFailure.retryAfterMs + 1
        val secondFailure = manager.login("000000", "555555", "ip-7", now) as LoginResult.InvalidPin

        val blocked = manager.login("555555", "555555", "ip-7", now + 100)
        assertTrue(blocked is LoginResult.Blocked)

        val success = manager.login("555555", "555555", "ip-7", now + secondFailure.retryAfterMs + 1)
        assertTrue(success is LoginResult.Success)

        val nextFailure = manager.login("111111", "555555", "ip-7", now + secondFailure.retryAfterMs + 2)
        assertTrue(nextFailure is LoginResult.InvalidPin)
        assertEquals(1000, (nextFailure as LoginResult.InvalidPin).retryAfterMs)
    }
}
