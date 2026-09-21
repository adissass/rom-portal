package com.romportal.app.server

import android.content.Context
import io.ktor.http.ContentType

internal data class WebAsset(
    val bytes: ByteArray,
    val contentType: ContentType
)

internal fun loadWebAsset(context: Context, path: String): WebAsset? {
    if (!webAssetPathIsSafe(path)) return null

    val bytes = runCatching {
        context.assets.open("web/$path").use { it.readBytes() }
    }.getOrNull() ?: return null

    return WebAsset(bytes, webAssetContentType(path))
}

internal fun webAssetPathIsSafe(path: String): Boolean {
    return path.isNotBlank() && path.split('/').all { segment ->
        segment.isNotBlank() && segment != "." && segment != ".." && !segment.contains('\\')
    }
}

private fun webAssetContentType(path: String): ContentType {
    return when (path.substringAfterLast('.', "").lowercase()) {
        "css" -> ContentType.Text.CSS
        "html" -> ContentType.Text.Html
        "js" -> ContentType.Application.JavaScript
        "json", "map" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "webp" -> ContentType("image", "webp")
        else -> ContentType.Application.OctetStream
    }
}
