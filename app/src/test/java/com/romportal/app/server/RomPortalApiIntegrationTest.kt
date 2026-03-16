package com.romportal.app.server

import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class RomPortalApiIntegrationTest {
    @Test
    fun authAndFileOps_happyPath() = testApplication {
        val fakeFileOps = FakeFileOpsGateway()
        application {
            configureRomPortalRoutes(
                RomPortalRouteConfig(
                    pin = "123456",
                    authManager = AuthManager(),
                    fileOps = fakeFileOps,
                    healthSnapshot = {
                        HealthSnapshot(
                            serverStartedAtEpochMs = 1_000,
                            uptimeMs = 5_000,
                            rootSelected = true,
                            rootUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
                            freeSpaceBytes = 123_456_789,
                            activeSessions = 0
                        )
                    },
                    loginPageHtml = { "<html><body>login</body></html>" },
                    fileManagerPageHtml = { "<html><body>ok</body></html>" }
                )
            )
        }

        val unauthList = client.get("/api/list?path=")
        assertEquals(HttpStatusCode.Unauthorized, unauthList.status)

        val loginResponse = client.post("/login") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(FormDataContent(Parameters.build { append("pin", "123456") }))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)

        val cookie = loginResponse.headers[HttpHeaders.SetCookie]
            ?.substringBefore(';')
            ?: error("missing auth cookie")

        val mkdirResponse = client.post("/api/mkdir") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(FormDataContent(Parameters.build { append("path", "TestDir") }))
        }
        assertEquals(HttpStatusCode.OK, mkdirResponse.status)

        val renameResponse = client.post("/api/rename") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("path", "TestDir")
                        append("newName", "TestDir2")
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.OK, renameResponse.status)

        val deleteResponse = client.post("/api/delete") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(FormDataContent(Parameters.build { append("path", "TestDir2") }))
        }
        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        val uploadResponse = client.post("/api/upload?path=") {
            header(HttpHeaders.Cookie, cookie)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            key = "file",
                            value = "hello".toByteArray(),
                            headers = Headers.build {
                                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"hello.txt\"")
                                append(HttpHeaders.ContentType, "text/plain")
                            }
                        )
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.OK, uploadResponse.status)

        val downloadResponse = client.get("/api/download?path=hello.txt") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.OK, downloadResponse.status)
        assertEquals("hello", downloadResponse.bodyAsText())

        val authedList = client.get("/api/list?path=") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.OK, authedList.status)
        assertTrue(authedList.bodyAsText().contains("hello.txt"))
    }

    @Test
    fun uploadDownload_preservesBytesExactly() = testApplication {
        val fakeFileOps = FakeFileOpsGateway()
        application {
            configureRomPortalRoutes(
                RomPortalRouteConfig(
                    pin = "987654",
                    authManager = AuthManager(),
                    fileOps = fakeFileOps,
                    healthSnapshot = {
                        HealthSnapshot(
                            serverStartedAtEpochMs = 2_000,
                            uptimeMs = 1_500,
                            rootSelected = true,
                            rootUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
                            freeSpaceBytes = 987_654_321,
                            activeSessions = 0
                        )
                    },
                    loginPageHtml = { "<html><body>login</body></html>" },
                    fileManagerPageHtml = { "<html><body>ok</body></html>" }
                )
            )
        }

        val loginResponse = client.post("/login") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(FormDataContent(Parameters.build { append("pin", "987654") }))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)

        val cookie = loginResponse.headers[HttpHeaders.SetCookie]
            ?.substringBefore(';')
            ?: error("missing auth cookie")

        val payload = ByteArray(4096)
        Random(1337).nextBytes(payload)

        val uploadResponse = client.post("/api/upload?path=") {
            header(HttpHeaders.Cookie, cookie)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            key = "file",
                            value = payload,
                            headers = Headers.build {
                                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"payload.bin\"")
                                append(HttpHeaders.ContentType, "application/octet-stream")
                            }
                        )
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.OK, uploadResponse.status)

        val downloadResponse = client.get("/api/download?path=payload.bin") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.OK, downloadResponse.status)

        val downloaded = downloadResponse.body<ByteArray>()
        assertEquals(payload.size, downloaded.size)
        assertTrue(payload.contentEquals(downloaded))
    }

    @Test
    fun negativePaths_enforceAuthAndValidation() = testApplication {
        val fakeFileOps = FakeFileOpsGateway()
        application {
            configureRomPortalRoutes(
                RomPortalRouteConfig(
                    pin = "111111",
                    authManager = AuthManager(),
                    fileOps = fakeFileOps,
                    healthSnapshot = {
                        HealthSnapshot(
                            serverStartedAtEpochMs = 3_000,
                            uptimeMs = 9_999,
                            rootSelected = false,
                            rootUri = null,
                            freeSpaceBytes = null,
                            activeSessions = 0
                        )
                    },
                    loginPageHtml = { "<html><body>login</body></html>" },
                    fileManagerPageHtml = { "<html><body>ok</body></html>" }
                )
            )
        }

        val unauth = client.get("/api/list?path=")
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)

        val loginResponse = client.post("/login") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(FormDataContent(Parameters.build { append("pin", "111111") }))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val cookie = loginResponse.headers[HttpHeaders.SetCookie]
            ?.substringBefore(';')
            ?: error("missing auth cookie")

        val traversal = client.get("/api/list?path=../") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.BadRequest, traversal.status)

        val mkdirA = client.post("/api/mkdir") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(FormDataContent(Parameters.build { append("path", "A") }))
        }
        assertEquals(HttpStatusCode.OK, mkdirA.status)

        val mkdirB = client.post("/api/mkdir") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(FormDataContent(Parameters.build { append("path", "B") }))
        }
        assertEquals(HttpStatusCode.OK, mkdirB.status)

        val renameConflict = client.post("/api/rename") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("path", "A")
                        append("newName", "B")
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, renameConflict.status)

        val missingDownload = client.get("/api/download?path=missing.bin") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.NotFound, missingDownload.status)

        val uploadWithoutFilePart = client.post("/api/upload?path=") {
            header(HttpHeaders.Cookie, cookie)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("note", "no file attached")
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, uploadWithoutFilePart.status)
    }

    @Test
    fun health_returnsStructuredSnapshot() = testApplication {
        application {
            configureRomPortalRoutes(
                RomPortalRouteConfig(
                    pin = "123123",
                    authManager = AuthManager(),
                    fileOps = FakeFileOpsGateway(),
                    healthSnapshot = {
                        HealthSnapshot(
                            status = "ok",
                            serverStartedAtEpochMs = 10_000,
                            uptimeMs = 321,
                            rootSelected = false,
                            rootUri = null,
                            freeSpaceBytes = null,
                            activeSessions = 2
                        )
                    },
                    loginPageHtml = { "<html><body>login</body></html>" },
                    fileManagerPageHtml = { "<html><body>ok</body></html>" }
                )
            )
        }

        val response = client.get("/health")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("\"status\":\"ok\""))
        assertTrue(body.contains("\"serverStartedAtEpochMs\":10000"))
        assertTrue(body.contains("\"uptimeMs\":321"))
        assertTrue(body.contains("\"rootSelected\":false"))
        assertTrue(body.contains("\"rootUri\":null"))
        assertTrue(body.contains("\"freeSpaceBytes\":null"))
        assertTrue(body.contains("\"activeSessions\":2"))
    }

    @Test
    fun health_setsRequestIdHeader_andRotatesPerRequest() = testApplication {
        application {
            configureRomPortalRoutes(
                RomPortalRouteConfig(
                    pin = "456456",
                    authManager = AuthManager(),
                    fileOps = FakeFileOpsGateway(),
                    healthSnapshot = {
                        HealthSnapshot(
                            status = "ok",
                            serverStartedAtEpochMs = 10_000,
                            uptimeMs = 100,
                            rootSelected = false,
                            rootUri = null,
                            freeSpaceBytes = null,
                            activeSessions = 0
                        )
                    },
                    loginPageHtml = { "<html><body>login</body></html>" },
                    fileManagerPageHtml = { "<html><body>ok</body></html>" }
                )
            )
        }

        val first = client.get("/health")
        val second = client.get("/health")

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)

        val firstRequestId = first.headers[HttpHeaders.XRequestId]
        val secondRequestId = second.headers[HttpHeaders.XRequestId]

        assertTrue(!firstRequestId.isNullOrBlank())
        assertTrue(!secondRequestId.isNullOrBlank())
        assertTrue(firstRequestId != secondRequestId)
    }

    @Test
    fun apiList_returnsUnauthorized_whenSessionTtlExpires() = testApplication {
        val authManager = AuthManager(
            config = AuthConfig(
                sessionTtlMs = 20,
                inactivityTimeoutMs = 10_000
            )
        )

        application {
            configureRomPortalRoutes(
                RomPortalRouteConfig(
                    pin = "222333",
                    authManager = authManager,
                    fileOps = FakeFileOpsGateway(),
                    healthSnapshot = {
                        HealthSnapshot(
                            status = "ok",
                            serverStartedAtEpochMs = 10_000,
                            uptimeMs = 100,
                            rootSelected = false,
                            rootUri = null,
                            freeSpaceBytes = null,
                            activeSessions = 0
                        )
                    },
                    loginPageHtml = { "<html><body>login</body></html>" },
                    fileManagerPageHtml = { "<html><body>ok</body></html>" }
                )
            )
        }

        val loginResponse = client.post("/login") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(FormDataContent(Parameters.build { append("pin", "222333") }))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val cookie = loginResponse.headers[HttpHeaders.SetCookie]?.substringBefore(';')
            ?: error("missing auth cookie")

        Thread.sleep(40)

        val expired = client.get("/api/list?path=") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.Unauthorized, expired.status)
    }

    @Test
    fun apiList_returnsUnauthorized_whenSessionInactiveTooLong() = testApplication {
        val authManager = AuthManager(
            config = AuthConfig(
                sessionTtlMs = 60_000,
                inactivityTimeoutMs = 200
            )
        )

        application {
            configureRomPortalRoutes(
                RomPortalRouteConfig(
                    pin = "333444",
                    authManager = authManager,
                    fileOps = FakeFileOpsGateway(),
                    healthSnapshot = {
                        HealthSnapshot(
                            status = "ok",
                            serverStartedAtEpochMs = 10_000,
                            uptimeMs = 100,
                            rootSelected = false,
                            rootUri = null,
                            freeSpaceBytes = null,
                            activeSessions = 0
                        )
                    },
                    loginPageHtml = { "<html><body>login</body></html>" },
                    fileManagerPageHtml = { "<html><body>ok</body></html>" }
                )
            )
        }

        val loginResponse = client.post("/login") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(FormDataContent(Parameters.build { append("pin", "333444") }))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val cookie = loginResponse.headers[HttpHeaders.SetCookie]?.substringBefore(';')
            ?: error("missing auth cookie")

        val firstAccess = client.get("/api/list?path=") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.OK, firstAccess.status)

        Thread.sleep(260)

        val expiredByInactivity = client.get("/api/list?path=") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.Unauthorized, expiredByInactivity.status)
    }

    @Test
    fun invalidOrMissingSessionCookie_returns401AcrossApiEndpoints() = testApplication {
        application {
            configureRomPortalRoutes(
                RomPortalRouteConfig(
                    pin = "654321",
                    authManager = AuthManager(),
                    fileOps = FakeFileOpsGateway(),
                    healthSnapshot = {
                        HealthSnapshot(
                            status = "ok",
                            serverStartedAtEpochMs = 10_000,
                            uptimeMs = 100,
                            rootSelected = false,
                            rootUri = null,
                            freeSpaceBytes = null,
                            activeSessions = 0
                        )
                    },
                    loginPageHtml = { "<html><body>login</body></html>" },
                    fileManagerPageHtml = { "<html><body>ok</body></html>" }
                )
            )
        }

        val unauthorizedCookies = listOf<String?>(null, "bad=1", "rs_session=invalid-token")

        unauthorizedCookies.forEach { cookie ->
            val listResponse = client.get("/api/list?path=") {
                cookie?.let { header(HttpHeaders.Cookie, it) }
            }
            assertEquals(HttpStatusCode.Unauthorized, listResponse.status)

            val mkdirResponse = client.post("/api/mkdir") {
                cookie?.let { header(HttpHeaders.Cookie, it) }
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                setBody(FormDataContent(Parameters.build { append("path", "Tmp") }))
            }
            assertEquals(HttpStatusCode.Unauthorized, mkdirResponse.status)

            val downloadResponse = client.get("/api/download?path=missing.bin") {
                cookie?.let { header(HttpHeaders.Cookie, it) }
            }
            assertEquals(HttpStatusCode.Unauthorized, downloadResponse.status)
        }
    }
}

private class FakeFileOpsGateway : FileOpsGateway {
    private val directories = linkedSetOf("")
    private val files = linkedMapOf<String, ByteArray>()

    override fun list(path: String?): Result<List<EntryInfo>> = runCatchingApi {
        val normalized = normalize(path)
        if (!directories.contains(normalized)) {
            throw FileApiException(HttpStatusCode.NotFound, "Path not found")
        }

        val entries = mutableListOf<EntryInfo>()
        val childDirs = directories
            .filter { it.isNotBlank() && parentOf(it) == normalized }
            .map { it.substringAfterLast('/') }
            .sorted()
            .map { EntryInfo(name = it, isDirectory = true, sizeBytes = 0) }

        val childFiles = files.keys
            .filter { parentOf(it) == normalized }
            .map { pathKey ->
                val name = pathKey.substringAfterLast('/')
                EntryInfo(name = name, isDirectory = false, sizeBytes = files[pathKey]?.size?.toLong() ?: 0)
            }
            .sortedBy { it.name }

        entries += childDirs
        entries += childFiles
        entries
    }

    override fun mkdir(path: String): Result<Unit> = runCatchingApi {
        val segments = normalizePathSegments(path)
        var current = ""
        for (segment in segments) {
            current = join(current, segment)
            directories.add(current)
        }
    }

    override fun rename(path: String, newName: String): Result<Unit> = runCatchingApi {
        val source = normalize(path)
        val parent = parentOf(source)
        val target = join(parent, newName.trim())

        if (directories.contains(source)) {
            if (directories.contains(target) || files.containsKey(target)) {
                throw FileApiException(HttpStatusCode.Conflict, "Target already exists")
            }
            val oldDirs = directories.filter { it == source || it.startsWith("$source/") }
            val oldFiles = files.keys.filter { it.startsWith("$source/") }

            for (dir in oldDirs) directories.remove(dir)
            val dirMappings = oldDirs.associateWith { it.replaceFirst(source, target) }
            directories.addAll(dirMappings.values)

            val fileMappings = oldFiles.associateWith { it.replaceFirst(source, target) }
            for ((oldPath, newPath) in fileMappings) {
                val bytes = files.remove(oldPath) ?: continue
                files[newPath] = bytes
            }
            return@runCatchingApi
        }

        val payload = files.remove(source)
            ?: throw FileApiException(HttpStatusCode.NotFound, "Path not found")
        if (files.containsKey(target) || directories.contains(target)) {
            files[source] = payload
            throw FileApiException(HttpStatusCode.Conflict, "Target already exists")
        }
        files[target] = payload
    }

    override fun delete(path: String): Result<Unit> = runCatchingApi {
        val normalized = normalize(path)
        if (files.remove(normalized) != null) return@runCatchingApi

        if (!directories.contains(normalized)) {
            throw FileApiException(HttpStatusCode.NotFound, "Path not found")
        }

        val dirsToRemove = directories.filter { it == normalized || it.startsWith("$normalized/") }
        val filesToRemove = files.keys.filter { it.startsWith("$normalized/") }
        dirsToRemove.forEach { directories.remove(it) }
        filesToRemove.forEach { files.remove(it) }
    }

    override fun openDownload(path: String): Result<Pair<String, java.io.InputStream>> = runCatchingApi {
        val normalized = normalize(path)
        val bytes = files[normalized] ?: throw FileApiException(HttpStatusCode.NotFound, "Path not found")
        val name = normalized.substringAfterLast('/')
        Pair(name, ByteArrayInputStream(bytes))
    }

    override fun upload(
        destinationPath: String?,
        filename: String,
        input: java.io.InputStream,
        contentLength: Long?
    ): Result<Unit> = runCatchingApi {
        val dir = normalize(destinationPath)
        if (!directories.contains(dir)) {
            throw FileApiException(HttpStatusCode.NotFound, "Destination not found")
        }

        val target = join(dir, filename)
        if (files.containsKey(target) || directories.contains(target)) {
            throw FileApiException(HttpStatusCode.Conflict, "File already exists")
        }

        val bytes = input.readBytes()
        if (contentLength != null && contentLength != bytes.size.toLong()) {
            throw FileApiException(HttpStatusCode.BadRequest, "Partial upload detected")
        }
        files[target] = bytes
    }

    private fun normalize(path: String?): String {
        val segments = normalizePathSegments(path)
        return segments.joinToString("/")
    }

    private fun join(parent: String, name: String): String {
        if (parent.isBlank()) return name
        return "$parent/$name"
    }

    private fun parentOf(path: String): String {
        if (!path.contains('/')) return ""
        return path.substringBeforeLast('/')
    }

    private inline fun <T> runCatchingApi(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: FileApiException) {
            Result.failure(e)
        }
    }
}
