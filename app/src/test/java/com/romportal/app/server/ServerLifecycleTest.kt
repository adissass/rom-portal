package com.romportal.app.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.URL

class ServerLifecycleTest {
    @Test
    fun serverStopsAcceptingRequestsAfterStop() {
        val port = ServerSocket(0).use { it.localPort }
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            configureRomPortalRoutes(
                RomPortalRouteConfig(
                    pin = "123456",
                    authManager = AuthManager(),
                    fileOps = NoopFileOpsGateway,
                    loginPageHtml = { "<html><body>login</body></html>" },
                    fileManagerPageHtml = { "<html><body>ok</body></html>" }
                )
            )
        }

        engine.start(wait = false)

        val healthUrl = URL("http://127.0.0.1:$port/health")
        val beforeStopCode = (healthUrl.openConnection() as java.net.HttpURLConnection).run {
            requestMethod = "GET"
            connectTimeout = 2000
            readTimeout = 2000
            responseCode
        }
        assertEquals(HttpStatusCode.OK.value, beforeStopCode)

        engine.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)

        val failed = try {
            (healthUrl.openConnection() as java.net.HttpURLConnection).run {
                requestMethod = "GET"
                connectTimeout = 1000
                readTimeout = 1000
                responseCode
            }
            false
        } catch (_: IOException) {
            true
        }

        assertTrue("Expected request to fail after server stop", failed)
    }
}

private object NoopFileOpsGateway : FileOpsGateway {
    override fun list(path: String?) = Result.success(emptyList<EntryInfo>())
    override fun mkdir(path: String) = Result.success(Unit)
    override fun rename(path: String, newName: String) = Result.success(Unit)
    override fun delete(path: String) = Result.success(Unit)
    override fun openDownload(path: String) = Result.failure<Pair<String, java.io.InputStream>>(
        FileApiException(HttpStatusCode.NotFound, "Path not found")
    )

    override fun upload(destinationPath: String?, filename: String, input: java.io.InputStream, contentLength: Long?) =
        Result.success(Unit)
}
