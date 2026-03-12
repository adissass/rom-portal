package com.romportal.app.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.header
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.cio.CIO
import java.net.NetworkInterface
import java.security.SecureRandom

internal data class ServerState(
    val isRunning: Boolean,
    val port: Int,
    val lanUrl: String,
    val pin: String
)

internal class RomPortalServer(
    private val preferredPort: Int = 8080,
    private val authManager: AuthManager = AuthManager(),
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private var engine: ApplicationEngine? = null
    private var state: ServerState? = null

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
                    val now = System.currentTimeMillis()
                    val token = call.request.cookies["rs_session"]
                    if (!authManager.isSessionValid(token, now)) {
                        call.respondRedirect("/login")
                        return@get
                    }

                    call.respondText(fileManagerStubHtml(), ContentType.Text.Html)
                }

                route("/api") {
                    get("/ping") {
                        val token = call.request.cookies["rs_session"]
                        if (!authManager.isSessionValid(token, System.currentTimeMillis())) {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                            return@get
                        }
                        call.respondText("ok", ContentType.Text.Plain)
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
              <p>Authenticated. File APIs come in the next step.</p>
            </body>
            </html>
        """.trimIndent()
    }
}
