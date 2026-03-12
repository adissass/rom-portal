package com.romportal.app.server

import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Test

class PathValidationTest {
    @Test
    fun normalizePath_rejectsAbsolutePath() {
        val ex = runCatching { normalizePathSegments("/ROMs") }.exceptionOrNull() as FileApiException
        assertEquals(HttpStatusCode.BadRequest, ex.status)
    }

    @Test
    fun normalizePath_rejectsTraversal() {
        val ex = runCatching { normalizePathSegments("ROMs/../BIOS") }.exceptionOrNull() as FileApiException
        assertEquals(HttpStatusCode.BadRequest, ex.status)
    }

    @Test
    fun normalizePath_acceptsSimplePath() {
        val result = normalizePathSegments("ROMs/PS1")
        assertEquals(listOf("ROMs", "PS1"), result)
    }
}
