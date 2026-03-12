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
            <head>
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>RomPortal File Manager</title>
              <style>
                :root {
                  --bg: #f6f7fb;
                  --panel: #ffffff;
                  --text: #1f2937;
                  --muted: #6b7280;
                  --line: #e5e7eb;
                  --accent: #0f766e;
                  --danger: #b91c1c;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  padding: 20px;
                  font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif;
                  color: var(--text);
                  background: linear-gradient(180deg, #f8fafc 0%, #eef2ff 100%);
                }
                .wrap { max-width: 960px; margin: 0 auto; }
                .panel {
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 12px;
                  padding: 16px;
                  margin-bottom: 16px;
                }
                h1 { margin: 0 0 12px 0; font-size: 24px; }
                .row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
                .muted { color: var(--muted); }
                button, input[type="text"] {
                  border: 1px solid var(--line);
                  border-radius: 8px;
                  padding: 8px 10px;
                  font-size: 14px;
                }
                button {
                  background: #fff;
                  cursor: pointer;
                }
                button.primary { background: var(--accent); color: #fff; border-color: var(--accent); }
                button.danger { color: var(--danger); border-color: #fecaca; }
                table { width: 100%; border-collapse: collapse; }
                th, td { padding: 10px 8px; border-bottom: 1px solid var(--line); text-align: left; font-size: 14px; }
                th { color: var(--muted); font-weight: 600; }
                .path { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; }
                .status { font-size: 13px; margin-top: 8px; color: var(--muted); min-height: 20px; }
                .status.error { color: var(--danger); }
                .linklike { color: var(--accent); text-decoration: underline; cursor: pointer; border: 0; background: transparent; padding: 0; }
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="panel">
                  <h1>RomPortal</h1>
                  <div class="row">
                    <span class="muted">Path:</span>
                    <span id="pathLabel" class="path"></span>
                  </div>
                  <div class="row" style="margin-top:10px;">
                    <button id="upBtn">Up</button>
                    <input id="mkdirInput" type="text" placeholder="New folder name" />
                    <button id="mkdirBtn">Create Folder</button>
                  </div>
                  <div class="row" style="margin-top:10px;">
                    <input id="uploadInput" type="file" multiple />
                    <button id="uploadBtn" class="primary">Upload</button>
                  </div>
                  <div id="status" class="status"></div>
                </div>
                <div class="panel">
                  <table>
                    <thead>
                      <tr>
                        <th>Name</th>
                        <th>Type</th>
                        <th>Size</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody id="entriesBody"></tbody>
                  </table>
                </div>
              </div>
              <script>
                let currentPath = "";
                const pathLabel = document.getElementById("pathLabel");
                const statusEl = document.getElementById("status");
                const entriesBody = document.getElementById("entriesBody");
                const mkdirInput = document.getElementById("mkdirInput");
                const uploadInput = document.getElementById("uploadInput");

                function setStatus(message, isError) {
                  statusEl.textContent = message || "";
                  statusEl.className = isError ? "status error" : "status";
                }

                function joinPath(base, name) {
                  if (!base) return name;
                  return base + "/" + name;
                }

                function parentPath(path) {
                  if (!path) return "";
                  const parts = path.split("/").filter(Boolean);
                  parts.pop();
                  return parts.join("/");
                }

                function formatBytes(bytes) {
                  if (!bytes || bytes < 0) return "-";
                  if (bytes < 1024) return bytes + " B";
                  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
                  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + " MB";
                  return (bytes / (1024 * 1024 * 1024)).toFixed(1) + " GB";
                }

                async function apiJson(url, options) {
                  const response = await fetch(url, options || {});
                  const text = await response.text();
                  let data = null;
                  try { data = text ? JSON.parse(text) : null; } catch (_) {}
                  if (!response.ok) {
                    const message = (data && data.error) ? data.error : (text || ("HTTP " + response.status));
                    throw new Error(message);
                  }
                  return data;
                }

                async function refreshList() {
                  try {
                    setStatus("Loading...", false);
                    pathLabel.textContent = "/" + currentPath;
                    const data = await apiJson("/api/list?path=" + encodeURIComponent(currentPath));
                    renderEntries((data && data.entries) ? data.entries : []);
                    setStatus("Ready", false);
                  } catch (e) {
                    setStatus(e.message, true);
                  }
                }

                function renderEntries(entries) {
                  entriesBody.innerHTML = "";

                  if (!entries.length) {
                    const row = document.createElement("tr");
                    const cell = document.createElement("td");
                    cell.colSpan = 4;
                    cell.className = "muted";
                    cell.textContent = "No files or folders";
                    row.appendChild(cell);
                    entriesBody.appendChild(row);
                    return;
                  }

                  for (const entry of entries) {
                    const row = document.createElement("tr");

                    const nameCell = document.createElement("td");
                    if (entry.isDirectory) {
                      const openBtn = document.createElement("button");
                      openBtn.className = "linklike";
                      openBtn.textContent = entry.name;
                      openBtn.onclick = () => {
                        currentPath = joinPath(currentPath, entry.name);
                        refreshList();
                      };
                      nameCell.appendChild(openBtn);
                    } else {
                      nameCell.textContent = entry.name;
                    }

                    const typeCell = document.createElement("td");
                    typeCell.textContent = entry.isDirectory ? "Folder" : "File";

                    const sizeCell = document.createElement("td");
                    sizeCell.textContent = entry.isDirectory ? "-" : formatBytes(entry.sizeBytes);

                    const actionsCell = document.createElement("td");

                    if (!entry.isDirectory) {
                      const dlBtn = document.createElement("button");
                      dlBtn.textContent = "Download";
                      dlBtn.onclick = () => {
                        const path = joinPath(currentPath, entry.name);
                        window.location.href = "/api/download?path=" + encodeURIComponent(path);
                      };
                      actionsCell.appendChild(dlBtn);
                    }

                    const renameBtn = document.createElement("button");
                    renameBtn.textContent = "Rename";
                    renameBtn.onclick = async () => {
                      const newName = prompt("New name", entry.name);
                      if (!newName || newName === entry.name) return;
                      try {
                        const path = joinPath(currentPath, entry.name);
                        await apiJson("/api/rename", {
                          method: "POST",
                          headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
                          body: new URLSearchParams({ path: path, newName: newName })
                        });
                        await refreshList();
                      } catch (e) {
                        setStatus(e.message, true);
                      }
                    };
                    actionsCell.appendChild(renameBtn);

                    const deleteBtn = document.createElement("button");
                    deleteBtn.className = "danger";
                    deleteBtn.textContent = "Delete";
                    deleteBtn.onclick = async () => {
                      if (!confirm("Delete " + entry.name + "?")) return;
                      try {
                        const path = joinPath(currentPath, entry.name);
                        await apiJson("/api/delete", {
                          method: "POST",
                          headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
                          body: new URLSearchParams({ path: path })
                        });
                        await refreshList();
                      } catch (e) {
                        setStatus(e.message, true);
                      }
                    };
                    actionsCell.appendChild(deleteBtn);

                    row.appendChild(nameCell);
                    row.appendChild(typeCell);
                    row.appendChild(sizeCell);
                    row.appendChild(actionsCell);
                    entriesBody.appendChild(row);
                  }
                }

                document.getElementById("upBtn").onclick = () => {
                  currentPath = parentPath(currentPath);
                  refreshList();
                };

                document.getElementById("mkdirBtn").onclick = async () => {
                  const name = (mkdirInput.value || "").trim();
                  if (!name) return;
                  try {
                    const path = joinPath(currentPath, name);
                    await apiJson("/api/mkdir", {
                      method: "POST",
                      headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
                      body: new URLSearchParams({ path: path })
                    });
                    mkdirInput.value = "";
                    await refreshList();
                  } catch (e) {
                    setStatus(e.message, true);
                  }
                };

                document.getElementById("uploadBtn").onclick = async () => {
                  const files = Array.from(uploadInput.files || []);
                  if (!files.length) return;
                  try {
                    for (let i = 0; i < files.length; i++) {
                      const file = files[i];
                      setStatus("Uploading " + file.name + " (" + (i + 1) + "/" + files.length + ")", false);
                      const form = new FormData();
                      form.append("file", file, file.name);
                      const response = await fetch("/api/upload?path=" + encodeURIComponent(currentPath), {
                        method: "POST",
                        body: form
                      });
                      if (!response.ok) {
                        const text = await response.text();
                        throw new Error(text || ("Upload failed: HTTP " + response.status));
                      }
                    }
                    uploadInput.value = "";
                    await refreshList();
                  } catch (e) {
                    setStatus(e.message, true);
                  }
                };

                refreshList();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
