package com.romportal.app.server

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
}
