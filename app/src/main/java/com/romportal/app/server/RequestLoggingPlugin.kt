package com.romportal.app.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.log
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import java.util.UUID

private val requestIdKey = AttributeKey<String>("request-id")
private val requestStartNsKey = AttributeKey<Long>("request-start-ns")

internal val RequestLoggingPlugin = createApplicationPlugin(name = "RequestLoggingPlugin") {
    onCall { call ->
        runCatching {
            val requestId = UUID.randomUUID().toString()
            call.attributes.put(requestIdKey, requestId)
            call.attributes.put(requestStartNsKey, System.nanoTime())
            call.response.headers.append(HttpHeaders.XRequestId, requestId)
        }.onFailure { error ->
            call.application.log.warn("request logging setup failed: ${error.message}")
        }
    }

    onCallRespond { call, _ ->
        runCatching {
            val requestId = call.attributes.getOrNull(requestIdKey) ?: "unknown"
            val startNs = call.attributes.getOrNull(requestStartNsKey) ?: System.nanoTime()
            val latencyMs = (System.nanoTime() - startNs) / 1_000_000

            val method = call.request.local.method
            val path = call.request.path()
            val status = call.response.status()?.value ?: 200
            val bytesIn = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val bytesOut = call.response.headers[HttpHeaders.ContentLength]?.toLongOrNull()

            call.application.log.info(
                "requestId=$requestId method=$method path=$path status=$status latencyMs=$latencyMs bytesIn=${bytesIn ?: -1} bytesOut=${bytesOut ?: -1}"
            )
        }.onFailure { error ->
            call.application.log.warn("request logging emit failed: ${error.message}")
        }
    }
}
