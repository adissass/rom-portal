package com.romportal.app.server

internal interface FileOpsGateway {
    fun list(path: String?): Result<List<EntryInfo>>
    fun mkdir(path: String): Result<Unit>
    fun rename(path: String, newName: String): Result<Unit>
    fun delete(path: String): Result<Unit>
    fun openDownload(path: String): Result<Pair<String, java.io.InputStream>>
    fun upload(destinationPath: String?, filename: String, input: java.io.InputStream, contentLength: Long?): Result<Unit>
}
