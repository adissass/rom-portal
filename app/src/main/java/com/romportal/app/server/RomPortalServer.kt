package com.romportal.app.server

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
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
    private val maxUploadBytes: Long? = null,
    private val onAuthenticatedFileApiSuccess: () -> Unit = {},
    private val onTransferStarted: () -> Unit = {},
    private val onTransferFinished: () -> Unit = {}
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
        val lanHost = detectLanAddress()

        val startMs = System.currentTimeMillis()
        engine = embeddedServer(CIO, host = "0.0.0.0", port = preferredPort) {
            configureRomPortalRoutes(
                RomPortalRouteConfig(
                    pin = pin,
                    authManager = authManager,
                    fileOps = fileOpsService,
                    onAuthenticatedFileApiSuccess = onAuthenticatedFileApiSuccess,
                    onTransferStarted = onTransferStarted,
                    onTransferFinished = onTransferFinished,
                    healthSnapshot = { buildHealthSnapshot(startMs) },
                    loginPageHtml = { loginHtml() },
                    fileManagerPageHtml = { fileManagerStubHtml() }
                )
            )
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

    private fun buildHealthSnapshot(startMs: Long): HealthSnapshot {
        val now = System.currentTimeMillis()
        val rootUri = rootUriProvider().orEmpty().ifBlank { null }
        return HealthSnapshot(
            status = "ok",
            serverStartedAtEpochMs = startMs,
            uptimeMs = (now - startMs).coerceAtLeast(0L),
            rootSelected = rootUri != null,
            rootUri = rootUri?.let(::sanitizeRootUri),
            freeSpaceBytes = readBestEffortFreeSpaceBytes(),
            activeSessions = authManager.activeSessionCount()
        )
    }

    private fun sanitizeRootUri(uriString: String): String {
        return runCatching {
            val uri = Uri.parse(uriString)
            val authority = uri.authority.orEmpty()
            val docId = DocumentsContract.getTreeDocumentId(uri)
            if (authority.isBlank() && docId.isBlank()) {
                uri.scheme ?: "content"
            } else {
                "${uri.scheme ?: "content"}://$authority/tree/$docId"
            }
        }.getOrElse { "content://invalid-tree-uri" }
    }

    private fun readBestEffortFreeSpaceBytes(): Long? {
        return runCatching { context.cacheDir.usableSpace }.getOrNull()
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
                .crumbs { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
                .crumb-sep { color: var(--muted); }
                .status {
                  font-size: 13px;
                  margin-top: 8px;
                  min-height: 20px;
                  border-radius: 8px;
                  padding: 8px 10px;
                  border: 1px solid transparent;
                  background: transparent;
                }
                .status.info { color: #1e40af; background: #eff6ff; border-color: #bfdbfe; }
                .status.success { color: #166534; background: #f0fdf4; border-color: #bbf7d0; }
                .status.error { color: #991b1b; background: #fef2f2; border-color: #fecaca; }
                .linklike { color: var(--accent); text-decoration: underline; cursor: pointer; border: 0; background: transparent; padding: 0; }
                .upload-progress-wrap { margin-top: 10px; display: none; }
                .upload-progress-meta { font-size: 12px; color: var(--muted); margin-bottom: 6px; }
                .failed-uploads { margin-top: 10px; border: 1px solid #fecaca; background: #fff7f7; border-radius: 8px; padding: 8px 10px; }
                .failed-uploads h3 { margin: 0 0 6px 0; font-size: 13px; color: #991b1b; }
                .failed-uploads ul { margin: 0; padding-left: 18px; font-size: 12px; color: #7f1d1d; }
                progress { width: 100%; height: 10px; }
                .hidden { display: none !important; }
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="panel">
                  <h1>RomPortal</h1>
                  <div class="row">
                    <span class="muted">Path:</span>
                    <div id="breadcrumb" class="crumbs path"></div>
                  </div>
                  <div class="row" style="margin-top:10px;">
                    <input id="mkdirInput" type="text" placeholder="New folder name" />
                    <button id="mkdirBtn">Create Folder</button>
                  </div>
                  <div class="row" style="margin-top:10px;">
                    <input id="uploadInput" type="file" multiple />
                    <button id="uploadBtn" class="primary">Upload</button>
                    <button id="retryFailedBtn" class="hidden">Retry Failed Uploads</button>
                  </div>
                  <div id="uploadProgressWrap" class="upload-progress-wrap">
                    <div id="uploadProgressMeta" class="upload-progress-meta"></div>
                    <progress id="uploadProgress" value="0" max="100"></progress>
                  </div>
                  <div id="failedUploadsWrap" class="failed-uploads hidden">
                    <h3 id="failedUploadsTitle">Failed uploads</h3>
                    <ul id="failedUploadsList"></ul>
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
                const breadcrumbEl = document.getElementById("breadcrumb");
                const statusEl = document.getElementById("status");
                const entriesBody = document.getElementById("entriesBody");
                const mkdirInput = document.getElementById("mkdirInput");
                const uploadInput = document.getElementById("uploadInput");
                const uploadBtn = document.getElementById("uploadBtn");
                const retryFailedBtn = document.getElementById("retryFailedBtn");
                const uploadProgressWrap = document.getElementById("uploadProgressWrap");
                const uploadProgressMeta = document.getElementById("uploadProgressMeta");
                const uploadProgress = document.getElementById("uploadProgress");
                const failedUploadsWrap = document.getElementById("failedUploadsWrap");
                const failedUploadsTitle = document.getElementById("failedUploadsTitle");
                const failedUploadsList = document.getElementById("failedUploadsList");
                let authRedirectPending = false;
                let failedUploads = [];
                let uploadBusy = false;

                function setStatus(message, type) {
                  statusEl.textContent = message || "";
                  if (!message) {
                    statusEl.className = "status";
                    return;
                  }
                  statusEl.className = "status " + (type || "info");
                }

                function handleSessionExpired() {
                  if (authRedirectPending) return true;
                  authRedirectPending = true;
                  setStatus("Session expired. Redirecting to login...", "error");
                  setTimeout(() => {
                    window.location.href = "/login";
                  }, 600);
                  return true;
                }

                function handleApiError(error) {
                  if (error && error.status === 401) {
                    return handleSessionExpired();
                  }
                  const message = mapUserMessage(error ? error.status : null, (error && error.message) ? error.message : "");
                  setStatus(message, "error");
                  return false;
                }

                function joinPath(base, name) {
                  if (!base) return name;
                  return base + "/" + name;
                }

                function parseErrorText(text, fallback) {
                  let parsedMessage = text;
                  try {
                    const parsed = text ? JSON.parse(text) : null;
                    if (parsed && parsed.error) {
                      parsedMessage = parsed.error;
                    }
                  } catch (_) {}
                  return parsedMessage || fallback;
                }

                function sanitizePathValue(pathValue) {
                  return String(pathValue || "").split("/").filter(Boolean).join("/");
                }

                function updatePathInUrl() {
                  const params = new URLSearchParams(window.location.search);
                  if (currentPath) {
                    params.set("path", currentPath);
                  } else {
                    params.delete("path");
                  }
                  const query = params.toString();
                  const nextUrl = window.location.pathname + (query ? ("?" + query) : "");
                  window.history.replaceState(null, "", nextUrl);
                }

                function mapUserMessage(status, rawMessage) {
                  const message = String(rawMessage || "").trim();
                  const lower = message.toLowerCase();
                  if (status === 409 || lower.includes("already exists")) {
                    return "File or folder already exists. Rename and try again.";
                  }
                  if (status === 507 || lower.includes("insufficient")) {
                    return "Not enough free storage. Free space and retry.";
                  }
                  if (status === 400 && (lower.includes("traversal") || lower.includes("invalid path") || lower.includes("absolute"))) {
                    return "Invalid path or folder location.";
                  }
                  if (status === 0) {
                    return "Network error. Check connection and retry.";
                  }
                  if (!message) {
                    return "Unexpected error. Please retry.";
                  }
                  return message;
                }

                function setUploadUiBusy(isBusy) {
                  uploadBusy = isBusy;
                  uploadBtn.disabled = isBusy;
                  retryFailedBtn.disabled = isBusy;
                  uploadInput.disabled = isBusy;
                }

                function setRetryButtonVisible(visible) {
                  if (visible) {
                    retryFailedBtn.classList.remove("hidden");
                  } else {
                    retryFailedBtn.classList.add("hidden");
                  }
                }

                function renderFailedUploads() {
                  failedUploadsList.innerHTML = "";
                  if (!failedUploads.length) {
                    failedUploadsWrap.classList.add("hidden");
                    failedUploadsTitle.textContent = "Failed uploads";
                    return;
                  }
                  failedUploadsWrap.classList.remove("hidden");
                  failedUploadsTitle.textContent = "Failed uploads (" + failedUploads.length + ")";
                  for (const item of failedUploads) {
                    const li = document.createElement("li");
                    li.textContent = item.file.name + ": " + item.reason;
                    failedUploadsList.appendChild(li);
                  }
                }

                function showUploadProgress(label, percent) {
                  uploadProgressWrap.style.display = "block";
                  uploadProgressMeta.textContent = label;
                  uploadProgress.value = Math.max(0, Math.min(100, percent));
                }

                function hideUploadProgress() {
                  uploadProgressWrap.style.display = "none";
                  uploadProgressMeta.textContent = "";
                  uploadProgress.value = 0;
                }

                function uploadSingleFile(file, index, total) {
                  return new Promise((resolve, reject) => {
                    const xhr = new XMLHttpRequest();
                    xhr.open("POST", "/api/upload?path=" + encodeURIComponent(currentPath));
                    xhr.upload.onprogress = (event) => {
                      if (!event.lengthComputable) return;
                      const percent = Math.round((event.loaded / event.total) * 100);
                      showUploadProgress(
                        "Uploading " + file.name + " (" + (index + 1) + "/" + total + ") - " + percent + "%",
                        percent
                      );
                    };
                    xhr.onerror = () => {
                      const error = new Error("Network error during upload");
                      error.status = 0;
                      reject(error);
                    };
                    xhr.onload = () => {
                      if (xhr.status >= 200 && xhr.status < 300) {
                        resolve();
                        return;
                      }
                      const error = new Error(parseErrorText(xhr.responseText, "Upload failed: HTTP " + xhr.status));
                      error.status = xhr.status;
                      reject(error);
                    };
                    const form = new FormData();
                    form.append("file", file, file.name);
                    xhr.send(form);
                  });
                }

                async function runUploadQueue(files) {
                  if (!files.length) return;
                  setUploadUiBusy(true);
                  setRetryButtonVisible(false);
                  failedUploads = [];
                  renderFailedUploads();
                  try {
                    for (let i = 0; i < files.length; i++) {
                      const file = files[i];
                      try {
                        showUploadProgress("Uploading " + file.name + " (" + (i + 1) + "/" + files.length + ")", 0);
                        await uploadSingleFile(file, i, files.length);
                      } catch (e) {
                        const reason = mapUserMessage(e ? e.status : null, e ? e.message : "");
                        failedUploads.push({ file: file, reason: reason, status: e ? e.status : null });
                        if (e && e.status === 401) {
                          handleSessionExpired();
                          break;
                        }
                        setStatus("Upload failed for " + file.name + ": " + reason, "error");
                      }
                    }

                    await refreshList(false);
                    if (failedUploads.length > 0) {
                      setRetryButtonVisible(true);
                      renderFailedUploads();
                      setStatus("", "info");
                    } else {
                      renderFailedUploads();
                      setStatus("Upload completed (" + files.length + " file" + (files.length > 1 ? "s" : "") + ")", "success");
                    }
                  } finally {
                    setUploadUiBusy(false);
                    hideUploadProgress();
                  }
                }

                function sortEntriesStable(entries) {
                  return [...entries].sort((a, b) => {
                    if (a.isDirectory !== b.isDirectory) {
                      return a.isDirectory ? -1 : 1;
                    }
                    const aLower = String(a.name || "").toLowerCase();
                    const bLower = String(b.name || "").toLowerCase();
                    if (aLower < bLower) return -1;
                    if (aLower > bLower) return 1;
                    return String(a.name || "").localeCompare(String(b.name || ""));
                  });
                }

                function renderBreadcrumb() {
                  breadcrumbEl.innerHTML = "";

                  function addCrumb(label, pathValue) {
                    const btn = document.createElement("button");
                    btn.className = "linklike";
                    btn.textContent = label;
                    btn.onclick = () => {
                      currentPath = pathValue;
                      refreshList();
                    };
                    breadcrumbEl.appendChild(btn);
                  }

                  addCrumb("Root", "");
                  const segments = currentPath.split("/").filter(Boolean);
                  for (let i = 0; i < segments.length; i++) {
                    const sep = document.createElement("span");
                    sep.className = "crumb-sep";
                    sep.textContent = "/";
                    breadcrumbEl.appendChild(sep);
                    addCrumb(segments[i], segments.slice(0, i + 1).join("/"));
                  }
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
                    const error = new Error(message);
                    error.status = response.status;
                    throw error;
                  }
                  return data;
                }

                async function refreshList(showLoading = true) {
                  try {
                    if (showLoading) {
                      setStatus("Loading...", "info");
                    }
                    currentPath = sanitizePathValue(currentPath);
                    updatePathInUrl();
                    renderBreadcrumb();
                    const data = await apiJson("/api/list?path=" + encodeURIComponent(currentPath));
                    const entries = (data && data.entries) ? data.entries : [];
                    renderEntries(sortEntriesStable(entries));
                    if (showLoading) {
                      setStatus("Ready", "success");
                    }
                  } catch (e) {
                    handleApiError(e);
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
                      dlBtn.onclick = async () => {
                        try {
                          await apiJson("/api/ping");
                          const path = joinPath(currentPath, entry.name);
                          setStatus("Download started: " + entry.name, "info");
                          window.location.href = "/api/download?path=" + encodeURIComponent(path);
                          setTimeout(() => {
                            if (!authRedirectPending) {
                              setStatus("", "info");
                            }
                          }, 2000);
                        } catch (e) {
                          handleApiError(e);
                        }
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
                        await refreshList(false);
                        setStatus("Renamed " + entry.name + " to " + newName, "success");
                      } catch (e) {
                        handleApiError(e);
                      }
                    };
                    actionsCell.appendChild(renameBtn);

                    const deleteBtn = document.createElement("button");
                    deleteBtn.className = "danger";
                    deleteBtn.textContent = "Delete";
                    deleteBtn.onclick = async () => {
                      const entryPath = joinPath(currentPath, entry.name);
                      const entryType = entry.isDirectory ? "folder" : "file";
                      if (!confirm("Delete " + entryType + " '" + entryPath + "'? This is permanent.")) return;
                      try {
                        await apiJson("/api/delete", {
                          method: "POST",
                          headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
                          body: new URLSearchParams({ path: entryPath })
                        });
                        await refreshList(false);
                        setStatus("Deleted " + entryType + ": " + entryPath, "success");
                      } catch (e) {
                        handleApiError(e);
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
                    await refreshList(false);
                    setStatus("Created folder: " + name, "success");
                  } catch (e) {
                    handleApiError(e);
                  }
                };

                uploadBtn.onclick = async () => {
                  const files = Array.from(uploadInput.files || []);
                  if (!files.length || uploadBusy) return;
                  await runUploadQueue(files);
                  uploadInput.value = "";
                };

                retryFailedBtn.onclick = async () => {
                  if (uploadBusy || failedUploads.length === 0) return;
                  const retryBatch = failedUploads.map(item => item.file);
                  await runUploadQueue(retryBatch);
                };

                currentPath = sanitizePathValue(new URLSearchParams(window.location.search).get("path") || "");
                refreshList();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
