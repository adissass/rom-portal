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
                    <div id="breadcrumb" class="crumbs path"></div>
                  </div>
                  <div class="row" style="margin-top:10px;">
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
                const breadcrumbEl = document.getElementById("breadcrumb");
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
                    throw new Error(message);
                  }
                  return data;
                }

                async function refreshList() {
                  try {
                    setStatus("Loading...", false);
                    renderBreadcrumb();
                    const data = await apiJson("/api/list?path=" + encodeURIComponent(currentPath));
                    const entries = (data && data.entries) ? data.entries : [];
                    renderEntries(sortEntriesStable(entries));
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
