package com.romportal.app.server

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
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

internal data class RomPortalRouteConfig(
    val pin: String,
    val authManager: AuthManager,
    val fileOps: FileOpsGateway,
    val onAuthenticatedFileApiSuccess: () -> Unit = {},
    val onTransferStarted: () -> Unit = {},
    val onTransferFinished: () -> Unit = {},
    val healthSnapshot: () -> HealthSnapshot,
    val loginPageHtml: () -> String,
    val fileManagerPageHtml: () -> String
)

internal data class HealthSnapshot(
    val status: String = "ok",
    val serverStartedAtEpochMs: Long,
    val uptimeMs: Long,
    val rootSelected: Boolean,
    val rootUri: String?,
    val freeSpaceBytes: Long?,
    val activeSessions: Int
)

internal fun Application.configureRomPortalRoutes(config: RomPortalRouteConfig) {
    install(RequestLoggingPlugin)

    routing {
        get("/health") {
            call.respondText(
                config.healthSnapshot().toJson(),
                ContentType.Application.Json
            )
        }

        get("/login") {
            call.respondText(config.loginPageHtml(), ContentType.Text.Html)
        }

        post("/login") {
            val pinInput = call.receiveParameters()["pin"].orEmpty().trim()
            val now = System.currentTimeMillis()
            val key = call.request.local.remoteHost

            when (val result = config.authManager.login(pinInput, config.pin, key, now)) {
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
            if (!call.isAuthenticated(config.authManager)) {
                call.respondRedirect("/login")
                return@get
            }

            call.respondText(config.fileManagerPageHtml(), ContentType.Text.Html)
        }

        route("/api") {
            get("/ping") {
                if (!call.isAuthenticated(config.authManager)) {
                    call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                    return@get
                }
                call.respondText("ok", ContentType.Text.Plain)
            }

            get("/list") {
                if (!call.isAuthenticated(config.authManager)) {
                    call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                    return@get
                }

                val result = config.fileOps.list(call.request.queryParameters["path"])
                result.onSuccess { entries ->
                    config.onAuthenticatedFileApiSuccess()
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
                if (!call.isAuthenticated(config.authManager)) {
                    call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                    return@post
                }

                val path = call.receiveParameters()["path"].orEmpty()
                val result = config.fileOps.mkdir(path)
                result.onSuccess {
                    config.onAuthenticatedFileApiSuccess()
                    call.respondText("{\"ok\":true}", ContentType.Application.Json)
                }.onFailure { error ->
                    call.respondApiError(error)
                }
            }

            post("/rename") {
                if (!call.isAuthenticated(config.authManager)) {
                    call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                    return@post
                }

                val params = call.receiveParameters()
                val path = params["path"].orEmpty()
                val newName = params["newName"].orEmpty()
                val result = config.fileOps.rename(path, newName)
                result.onSuccess {
                    config.onAuthenticatedFileApiSuccess()
                    call.respondText("{\"ok\":true}", ContentType.Application.Json)
                }.onFailure { error ->
                    call.respondApiError(error)
                }
            }

            post("/delete") {
                if (!call.isAuthenticated(config.authManager)) {
                    call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                    return@post
                }

                val path = call.receiveParameters()["path"].orEmpty()
                val result = config.fileOps.delete(path)
                result.onSuccess {
                    config.onAuthenticatedFileApiSuccess()
                    call.respondText("{\"ok\":true}", ContentType.Application.Json)
                }.onFailure { error ->
                    call.respondApiError(error)
                }
            }

            get("/download") {
                if (!call.isAuthenticated(config.authManager)) {
                    call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                    return@get
                }

                val path = call.request.queryParameters["path"].orEmpty()
                val result = config.fileOps.openDownload(path)
                result.onSuccess { (name, input) ->
                    config.onTransferStarted()
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString()
                    )
                    try {
                        call.respondOutputStream(ContentType.Application.OctetStream) {
                            input.use { stream ->
                                stream.copyTo(this)
                            }
                        }
                    } finally {
                        config.onTransferFinished()
                    }
                }.onFailure { error ->
                    call.respondApiError(error)
                }
            }

            post("/upload") {
                if (!call.isAuthenticated(config.authManager)) {
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
                            config.onTransferStarted()
                            val result = try {
                                config.fileOps.upload(
                                    destinationPath = destinationPath,
                                    filename = filename,
                                    input = part.streamProvider(),
                                    contentLength = contentLength
                                )
                            } finally {
                                config.onTransferFinished()
                            }
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
}

private suspend fun io.ktor.server.application.ApplicationCall.isAuthenticated(authManager: AuthManager): Boolean {
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

private fun HealthSnapshot.toJson(): String {
    val rootUriJson = rootUri?.let { "\"${escapeJson(it)}\"" } ?: "null"
    val freeSpaceJson = freeSpaceBytes?.toString() ?: "null"
    return "{" +
        "\"status\":\"${escapeJson(status)}\"," +
        "\"serverStartedAtEpochMs\":$serverStartedAtEpochMs," +
        "\"uptimeMs\":$uptimeMs," +
        "\"rootSelected\":$rootSelected," +
        "\"rootUri\":$rootUriJson," +
        "\"freeSpaceBytes\":$freeSpaceJson," +
        "\"activeSessions\":$activeSessions" +
        "}"
}
