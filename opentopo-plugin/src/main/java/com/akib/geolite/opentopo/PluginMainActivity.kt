package com.akib.geolite.opentopo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import java.io.File

class PluginMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    var showDownloader by remember { mutableStateOf(true) }
                    if (showDownloader) {
                        OpenTopographyDemDownloaderDialog(
                            initialExtent = null,
                            outputDirectory = getExternalFilesDir("DEM") ?: filesDir,
                            onDismiss = { finish() },
                            onDownloaded = { file -> shareGeoTiff(file) }
                        )
                    }
                }
            }
        }
    }

    private fun shareGeoTiff(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "com.akib.geolite.plugin.opentopo.files",
            file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/tiff"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Open downloaded DEM with aGIS or another GIS app"))
    }
}
