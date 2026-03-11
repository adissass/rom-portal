package com.romportal.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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

private const val PREFS_NAME = "romportal_prefs"
private const val KEY_ROOT_URI = "selected_root_uri"

class MainActivity : ComponentActivity() {
    private var selectedRootUri by mutableStateOf<String?>(null)

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

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RomPortalHome(
                        selectedRootUri = selectedRootUri,
                        onPickFolder = { pickFolderLauncher.launch(null) }
                    )
                }
            }
        }
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
    onPickFolder: () -> Unit
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
        Text(text = stringResource(R.string.server_stopped), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onPickFolder) {
            Text(text = stringResource(R.string.pick_folder))
        }
    }
}

internal fun formatRootLabel(uriString: String?): String {
    if (uriString.isNullOrBlank()) return "No folder selected"

    return try {
        val uri = Uri.parse(uriString)
        val treeDocId = DocumentsContract.getTreeDocumentId(uri)
        val label = treeDocId.substringAfter(':', treeDocId)
        "Selected folder: $label"
    } catch (_: Exception) {
        "Selected folder"
    }
}
