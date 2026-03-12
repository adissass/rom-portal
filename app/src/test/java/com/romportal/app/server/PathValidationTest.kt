package com.romportal.app.server

import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Test

class PathValidationTest {
    @Test
    fun normalizePath_allowsBlankPathAsRoot() {
        val result = normalizePathSegments(" ")
        assertEquals(emptyList<String>(), result)
    }

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

    @Test
    fun normalizePath_ignoresRepeatedSeparators() {
        val result = normalizePathSegments("ROMs//PS1///Saves")
        assertEquals(listOf("ROMs", "PS1", "Saves"), result)
    }

    @Test
    fun normalizePath_rejectsBackslashInSegment() {
        val ex = runCatching { normalizePathSegments("ROMs\\PS1") }.exceptionOrNull() as FileApiException
        assertEquals(HttpStatusCode.BadRequest, ex.status)
    }
}
