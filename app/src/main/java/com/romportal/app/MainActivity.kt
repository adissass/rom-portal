package com.romportal.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.romportal.app.server.RomPortalServer
import com.romportal.app.server.ServerState
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val PREFS_NAME = "romportal_prefs"
private const val KEY_ROOT_URI = "selected_root_uri"

class MainActivity : ComponentActivity() {
    private var selectedRootUri by mutableStateOf<String?>(null)
    private var serverState by mutableStateOf<ServerState?>(null)
    private var serverError by mutableStateOf<String?>(null)

    private lateinit var romPortalServer: RomPortalServer

    private val pickFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult

            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // Some providers may not grant persistable permission; keep the selection anyway.
            }

            saveSelectedRootUri(uri.toString())
            selectedRootUri = uri.toString()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedRootUri = readSelectedRootUri()
        romPortalServer = RomPortalServer(
            context = applicationContext,
            contentResolver = contentResolver,
            rootUriProvider = { selectedRootUri }
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RomPortalHome(
                        selectedRootUri = selectedRootUri,
                        serverState = serverState,
                        serverError = serverError,
                        onPickFolder = { pickFolderLauncher.launch(null) },
                        onToggleServer = {
                            if (serverState == null) {
                                try {
                                    serverState = romPortalServer.start()
                                    serverError = null
                                } catch (e: Exception) {
                                    serverState = null
                                    serverError = e.message ?: "Failed to start server"
                                }
                            } else {
                                romPortalServer.stop()
                                serverState = null
                                serverError = null
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // MVP policy: server is foreground-only while app is open.
        if (::romPortalServer.isInitialized) {
            romPortalServer.stop()
        }
        serverState = null
    }

    private fun saveSelectedRootUri(uri: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_ROOT_URI, uri)
            .apply()
    }

    private fun readSelectedRootUri(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ROOT_URI, null)
    }
}

@Composable
private fun RomPortalHome(
    selectedRootUri: String?,
    serverState: ServerState?,
    serverError: String?,
    onPickFolder: () -> Unit,
    onToggleServer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = formatRootLabel(selectedRootUri),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        if (serverState == null) {
            Text(text = stringResource(R.string.server_stopped), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(
                text = stringResource(R.string.server_url_prefix, serverState.lanUrl),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.server_pin_prefix, serverState.pin),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        if (!serverError.isNullOrBlank()) {
            Text(text = serverError, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onPickFolder) {
            Text(text = stringResource(R.string.pick_folder))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onToggleServer) {
            Text(text = if (serverState == null) stringResource(R.string.start_server) else stringResource(R.string.stop_server))
        }
    }
}

internal fun formatRootLabel(uriString: String?): String {
    if (uriString.isNullOrBlank()) return "No folder selected"

    return try {
        val decoded = URLDecoder.decode(uriString, StandardCharsets.UTF_8.name())
        if (!decoded.contains("/tree/")) {
            return "Selected folder"
        }
        val treePart = decoded.substringAfter("/tree/", "")
        val treeDocId = treePart.substringBefore("/")
        val label = if (treeDocId.isBlank()) decoded else treeDocId
        val cleanLabel = label.substringAfter(':', label)
        if (cleanLabel.isBlank()) {
            return "Selected folder"
        }
        "Selected folder: $cleanLabel"
    } catch (_: Exception) {
        "Selected folder"
    }
}
