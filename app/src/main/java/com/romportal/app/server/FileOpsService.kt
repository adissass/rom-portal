package com.romportal.app.server

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.HttpStatusCode
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

internal data class EntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)

internal class FileOpsService(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val rootUriProvider: () -> String?,
    private val maxUploadBytes: Long? = null
) : FileOpsGateway {
    override fun list(path: String?): Result<List<EntryInfo>> = runCatchingApi {
        val directory = resolveExisting(path, mustBeDirectory = true)
        directory.listFiles().map { doc ->
            EntryInfo(
                name = doc.name ?: "(unnamed)",
                isDirectory = doc.isDirectory,
                sizeBytes = doc.length()
            )
        }.sortedWith(compareBy<EntryInfo> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override fun mkdir(path: String): Result<Unit> = runCatchingApi {
        val normalized = normalizePathSegments(path)
        val root = getRootDirectory()
        var current = root
        for (segment in normalized) {
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment)
                    ?: throw FileApiException(HttpStatusCode.InternalServerError, "Failed to create directory")

                existing.isDirectory -> existing
                else -> throw FileApiException(HttpStatusCode.Conflict, "Path conflicts with a file")
            }
        }
    }

    override fun delete(path: String): Result<Unit> = runCatchingApi {
        val target = resolveExisting(path, mustBeDirectory = null)
        if (!target.delete()) {
            throw FileApiException(HttpStatusCode.InternalServerError, "Failed to delete entry")
        }
    }

    override fun rename(path: String, newName: String): Result<Unit> = runCatchingApi {
        val safeName = normalizeName(newName)
        val target = resolveExisting(path, mustBeDirectory = null)
        val parent = resolveParent(path)

        if (parent.findFile(safeName) != null) {
            throw FileApiException(HttpStatusCode.Conflict, "Target already exists")
        }

        if (!target.renameTo(safeName)) {
            throw FileApiException(HttpStatusCode.InternalServerError, "Rename failed")
        }
    }

    override fun openDownload(path: String): Result<Pair<String, java.io.InputStream>> = runCatchingApi {
        val file = resolveExisting(path, mustBeDirectory = false)
        val name = file.name ?: "download.bin"
        val input = contentResolver.openInputStream(file.uri)
            ?: throw FileApiException(HttpStatusCode.InternalServerError, "Unable to open file stream")
        Pair(name, input)
    }

    override fun upload(destinationPath: String?, filename: String, input: java.io.InputStream, contentLength: Long?): Result<Unit> = runCatchingApi {
        val safeFilename = normalizeName(filename)
        val destinationDir = resolveExisting(destinationPath, mustBeDirectory = true)

        if (destinationDir.findFile(safeFilename) != null) {
            throw FileApiException(HttpStatusCode.Conflict, "File already exists")
        }

        if (maxUploadBytes != null && contentLength != null && contentLength > maxUploadBytes) {
            throw FileApiException(HttpStatusCode.PayloadTooLarge, "File exceeds max upload size")
        }

        if (contentLength != null && contentLength > context.cacheDir.usableSpace) {
            throw FileApiException(HttpStatusCode.InsufficientStorage, "Insufficient free storage")
        }

        val tempName = ".tmp-${UUID.randomUUID()}.part"
        val tempMime = guessMimeType(tempName)
        val tempDoc = destinationDir.createFile(tempMime, tempName)
            ?: throw FileApiException(HttpStatusCode.InternalServerError, "Unable to allocate temp file")

        var bytesWritten = 0L
        try {
            contentResolver.openOutputStream(tempDoc.uri, "w")?.use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytesWritten += read

                    if (maxUploadBytes != null && bytesWritten > maxUploadBytes) {
                        throw FileApiException(HttpStatusCode.PayloadTooLarge, "File exceeds max upload size")
                    }

                    out.write(buffer, 0, read)
                }
                out.flush()
            } ?: throw FileApiException(HttpStatusCode.InternalServerError, "Unable to open temp output stream")

            if (contentLength != null && contentLength != bytesWritten) {
                throw FileApiException(HttpStatusCode.BadRequest, "Partial upload detected")
            }

            if (!tempDoc.renameTo(safeFilename)) {
                throw FileApiException(HttpStatusCode.InternalServerError, "Failed to finalize upload")
            }
        } catch (e: FileApiException) {
            tempDoc.delete()
            throw e
        } catch (e: FileNotFoundException) {
            tempDoc.delete()
            throw FileApiException(HttpStatusCode.NotFound, "Destination not found")
        } catch (e: IOException) {
            tempDoc.delete()
            throw FileApiException(HttpStatusCode.InsufficientStorage, "Upload failed due to storage or stream error")
        } finally {
            input.close()
        }
    }

    private fun resolveExisting(path: String?, mustBeDirectory: Boolean?): DocumentFile {
        val segments = normalizePathSegments(path)
        var current = getRootDirectory()

        for (segment in segments) {
            current = current.findFile(segment)
                ?: throw FileApiException(HttpStatusCode.NotFound, "Path not found")
        }

        if (mustBeDirectory == true && !current.isDirectory) {
            throw FileApiException(HttpStatusCode.BadRequest, "Directory path required")
        }
        if (mustBeDirectory == false && current.isDirectory) {
            throw FileApiException(HttpStatusCode.BadRequest, "File path required")
        }

        return current
    }

    private fun resolveParent(path: String): DocumentFile {
        val normalized = normalizePathSegments(path)
        if (normalized.isEmpty()) {
            throw FileApiException(HttpStatusCode.BadRequest, "Root path cannot be renamed")
        }

        val parentSegments = normalized.dropLast(1)
        var current = getRootDirectory()
        for (segment in parentSegments) {
            current = current.findFile(segment)
                ?: throw FileApiException(HttpStatusCode.NotFound, "Parent path not found")
            if (!current.isDirectory) {
                throw FileApiException(HttpStatusCode.BadRequest, "Parent must be a directory")
            }
        }
        return current
    }

    private fun getRootDirectory(): DocumentFile {
        val uriString = rootUriProvider()?.takeIf { it.isNotBlank() }
            ?: throw FileApiException(HttpStatusCode.BadRequest, "No SAF root selected")
        val rootUri = Uri.parse(uriString)
        return DocumentFile.fromTreeUri(context, rootUri)
            ?: throw FileApiException(HttpStatusCode.BadRequest, "Invalid SAF root")
    }

    private fun normalizeName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            throw FileApiException(HttpStatusCode.BadRequest, "Name is required")
        }
        if (trimmed.contains('/')) {
            throw FileApiException(HttpStatusCode.BadRequest, "Name cannot contain '/'")
        }
        if (trimmed == "." || trimmed == "..") {
            throw FileApiException(HttpStatusCode.BadRequest, "Invalid name")
        }
        return trimmed
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private inline fun <T> runCatchingApi(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: FileApiException) {
            Result.failure(e)
        }
    }
}

internal class FileApiException(
    val status: HttpStatusCode,
    override val message: String
) : RuntimeException(message)

internal fun normalizePathSegments(path: String?): List<String> {
    if (path.isNullOrBlank()) return emptyList()

    val trimmed = path.trim()
    if (trimmed.startsWith("/")) {
        throw FileApiException(HttpStatusCode.BadRequest, "Absolute paths are not allowed")
    }

    return trimmed.split('/')
        .filter { it.isNotBlank() }
        .map { segment ->
            when {
                segment == "." || segment == ".." -> throw FileApiException(
                    HttpStatusCode.BadRequest,
                    "Traversal segments are not allowed"
                )

                segment.contains('\\') -> throw FileApiException(
                    HttpStatusCode.BadRequest,
                    "Invalid path segment"
                )

                else -> segment
            }
        }
}
