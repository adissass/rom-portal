package com.romportal.app.server

import android.content.ContentResolver
import android.content.Context
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.NetworkInterface
import java.security.SecureRandom

internal data class ServerState(
    val isRunning: Boolean,
    val port: Int,
    val lanUrl: String,
    val pin: String
)

internal class RomPortalServer(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val rootUriProvider: () -> String?,
    private val preferredPort: Int = 8080,
    private val authManager: AuthManager = AuthManager(),
    private val secureRandom: SecureRandom = SecureRandom(),
    private val maxUploadBytes: Long? = null
) {
    private var engine: ApplicationEngine? = null
    private var state: ServerState? = null

    private val fileOpsService by lazy {
        FileOpsService(
            context = context,
            contentResolver = contentResolver,
            rootUriProvider = rootUriProvider,
            maxUploadBytes = maxUploadBytes
        )
    }

    fun start(): ServerState {
        state?.let { return it }

        val pin = generatePin()
        val host = "0.0.0.0"
        val lanHost = detectLanAddress()

        engine = embeddedServer(CIO, host = host, port = preferredPort) {
            routing {
                get("/health") {
                    call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
                }

                get("/login") {
                    call.respondText(loginHtml(), ContentType.Text.Html)
                }

                post("/login") {
                    val pinInput = call.receiveParameters()["pin"].orEmpty().trim()
                    val now = System.currentTimeMillis()
                    val key = call.request.local.remoteHost

                    when (val result = authManager.login(pinInput, pin, key, now)) {
                        is LoginResult.Success -> {
                            val cookieHeader = buildString {
                                append("rs_session=")
                                append(result.token)
                                append("; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800")
                            }
                            call.response.header(HttpHeaders.SetCookie, cookieHeader)
                            call.respondText(
                                text = """
                                    <html>
                                    <head>
                                      <meta http-equiv="refresh" content="0;url=/" />
                                      <meta name="viewport" content="width=device-width,initial-scale=1" />
                                    </head>
                                    <body>
                                      <p>Login successful. Redirecting...</p>
                                      <p><a href="/">Continue</a></p>
                                    </body>
                                    </html>
                                """.trimIndent(),
                                contentType = ContentType.Text.Html,
                                status = HttpStatusCode.OK
                            )
                        }

                        is LoginResult.Blocked -> {
                            call.respondText(
                                text = "Too many attempts. Retry in ${result.retryAfterMs / 1000}s",
                                status = HttpStatusCode.TooManyRequests,
                                contentType = ContentType.Text.Plain
                            )
                        }

                        is LoginResult.InvalidPin -> {
                            call.respondText(
                                text = "Invalid PIN. Retry in ${result.retryAfterMs / 1000}s",
                                status = HttpStatusCode.Unauthorized,
                                contentType = ContentType.Text.Plain
                            )
                        }
                    }
                }

                get("/") {
                    if (!call.isAuthenticated()) {
                        call.respondRedirect("/login")
                        return@get
                    }

                    call.respondText(fileManagerStubHtml(), ContentType.Text.Html)
                }

                route("/api") {
                    get("/ping") {
                        if (!call.isAuthenticated()) {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                            return@get
                        }
                        call.respondText("ok", ContentType.Text.Plain)
                    }

                    get("/list") {
                        if (!call.isAuthenticated()) {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                            return@get
                        }

                        val result = fileOpsService.list(call.request.queryParameters["path"])
                        result.onSuccess { entries ->
                            call.respondText(
                                toEntriesJson(entries),
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.OK
                            )
                        }.onFailure { error ->
                            call.respondApiError(error)
                        }
                    }

                    post("/mkdir") {
                        if (!call.isAuthenticated()) {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                            return@post
                        }

                        val path = call.receiveParameters()["path"].orEmpty()
                        val result = fileOpsService.mkdir(path)
                        result.onSuccess {
                            call.respondText("{\"ok\":true}", ContentType.Application.Json)
                        }.onFailure { error ->
                            call.respondApiError(error)
                        }
                    }

                    post("/rename") {
                        if (!call.isAuthenticated()) {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                            return@post
                        }

                        val params = call.receiveParameters()
                        val path = params["path"].orEmpty()
                        val newName = params["newName"].orEmpty()
                        val result = fileOpsService.rename(path, newName)
                        result.onSuccess {
                            call.respondText("{\"ok\":true}", ContentType.Application.Json)
                        }.onFailure { error ->
                            call.respondApiError(error)
                        }
                    }

                    post("/delete") {
                        if (!call.isAuthenticated()) {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                            return@post
                        }

                        val path = call.receiveParameters()["path"].orEmpty()
                        val result = fileOpsService.delete(path)
                        result.onSuccess {
                            call.respondText("{\"ok\":true}", ContentType.Application.Json)
                        }.onFailure { error ->
                            call.respondApiError(error)
                        }
                    }

                    get("/download") {
                        if (!call.isAuthenticated()) {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                            return@get
                        }

                        val path = call.request.queryParameters["path"].orEmpty()
                        val result = fileOpsService.openDownload(path)
                        result.onSuccess { (name, input) ->
                            call.response.header(
                                HttpHeaders.ContentDisposition,
                                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString()
                            )
                            call.respondOutputStream(ContentType.Application.OctetStream) {
                                input.use { stream ->
                                    stream.copyTo(this)
                                }
                            }
                        }.onFailure { error ->
                            call.respondApiError(error)
                        }
                    }

                    post("/upload") {
                        if (!call.isAuthenticated()) {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                            return@post
                        }

                        val destinationPath = call.request.queryParameters["path"]
                        val multipart = call.receiveMultipart()
                        var uploaded = false
                        var uploadError: Throwable? = null

                        multipart.forEachPart { part ->
                            try {
                                if (part is PartData.FileItem && !uploaded) {
                                    val filename = part.originalFileName ?: "upload.bin"
                                    val contentLength = part.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                                    val result = fileOpsService.upload(
                                        destinationPath = destinationPath,
                                        filename = filename,
                                        input = part.streamProvider(),
                                        contentLength = contentLength
                                    )
                                    result.exceptionOrNull()?.let { uploadError = it }
                                    uploaded = result.isSuccess
                                }
                            } finally {
                                part.dispose()
                            }
                        }

                        if (uploadError != null) {
                            call.respondApiError(uploadError!!)
                            return@post
                        }

                        if (!uploaded) {
                            call.respondText(
                                "{\"error\":\"No file part found\"}",
                                status = HttpStatusCode.BadRequest,
                                contentType = ContentType.Application.Json
                            )
                            return@post
                        }

                        call.respondText("{\"ok\":true}", ContentType.Application.Json)
                    }
                }
            }
        }.start(wait = false)

        val newState = ServerState(
            isRunning = true,
            port = preferredPort,
            lanUrl = "http://$lanHost:$preferredPort",
            pin = pin
        )
        state = newState
        return newState
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
        engine = null
        state = null
        authManager.clear()
    }

    private suspend fun io.ktor.server.application.ApplicationCall.isAuthenticated(): Boolean {
        val token = request.cookies["rs_session"]
        return authManager.isSessionValid(token, System.currentTimeMillis())
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondApiError(error: Throwable) {
        val apiError = error as? FileApiException
        if (apiError == null) {
            respondText(
                "{\"error\":\"Internal server error\"}",
                status = HttpStatusCode.InternalServerError,
                contentType = ContentType.Application.Json
            )
            return
        }

        respondText(
            "{\"error\":\"${escapeJson(apiError.message)}\"}",
            status = apiError.status,
            contentType = ContentType.Application.Json
        )
    }

    private fun toEntriesJson(entries: List<EntryInfo>): String {
        val items = entries.joinToString(separator = ",") { entry ->
            "{" +
                "\"name\":\"${escapeJson(entry.name)}\"," +
                "\"isDirectory\":${entry.isDirectory}," +
                "\"sizeBytes\":${entry.sizeBytes}" +
                "}"
        }
        return "{\"entries\":[${items}]}"
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun generatePin(): String {
        val value = secureRandom.nextInt(900_000) + 100_000
        return value.toString()
    }

    private fun detectLanAddress(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        for (network in interfaces) {
            if (!network.isUp || network.isLoopback) continue
            for (address in network.inetAddresses) {
                if (address.isLoopbackAddress) continue
                val hostAddress = address.hostAddress ?: continue
                if (hostAddress.contains(':')) continue
                if (address.isSiteLocalAddress) {
                    return hostAddress
                }
            }
        }
        return "127.0.0.1"
    }

    private fun loginHtml(): String {
        return """
            <html>
            <head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body>
              <h1>RomPortal Login</h1>
              <form action="/login" method="post">
                <label for="pin">PIN</label>
                <input id="pin" name="pin" type="password" required />
                <button type="submit">Login</button>
              </form>
            </body>
            </html>
        """.trimIndent()
    }

    private fun fileManagerStubHtml(): String {
        return """
            <html>
            <head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body>
              <h1>RomPortal</h1>
              <p>Authenticated. File APIs are active.</p>
              <ul>
                <li>GET /api/list?path=</li>
                <li>POST /api/mkdir (path)</li>
                <li>POST /api/rename (path,newName)</li>
                <li>POST /api/delete (path)</li>
                <li>GET /api/download?path=</li>
                <li>POST /api/upload?path=</li>
              </ul>
            </body>
            </html>
        """.trimIndent()
    }
}
