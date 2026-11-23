package com.akib.geolite

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Native Android port of the OpenTopography DEM Downloader workflow.
 *
 * Original QGIS plugin: OpenTopography DEM Downloader by Kyaw Naing Win.
 * Original project: https://github.com/knwin/OpenTopography-DEM-Downloader-qgis-plugin
 *
 * The original plugin is GPL-2.0-or-later. This port retains attribution and is
 * intended to be distributed under the same license.
 */
internal data class DemExtent(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double
)

internal data class OpenTopographyDataset(val label: String, val code: String)

internal object OpenTopographyDemService {
    val datasets = listOf(
        OpenTopographyDataset("ANADEM · 30 m (South America)", "ANADEM"),
        OpenTopographyDataset("ALOS World 3D · 30 m", "AW3D30"),
        OpenTopographyDataset("ALOS World 3D Ellipsoidal · 30 m", "AW3D30_E"),
        OpenTopographyDataset("Copernicus Global DSM · 30 m", "COP30"),
        OpenTopographyDataset("Copernicus Global DSM · 90 m", "COP90"),
        OpenTopographyDataset("Europe DTM · 30 m", "EU_DTM"),
        OpenTopographyDataset("GEBCO IceTopo Bathymetry · 500 m", "GEBCOIceTopo"),
        OpenTopographyDataset("GEBCO SubIceTopo Bathymetry · 500 m", "GEBCOSubIceTopo"),
        OpenTopographyDataset("GEDI L3 Grid · 1 km", "GEDI_L3"),
        OpenTopographyDataset("Global Bathymetry SRTM15+ V2.55 · 500 m", "SRTM15Plus"),
        OpenTopographyDataset("Global Ensemble DTM · 30 m", "GEDTM30"),
        OpenTopographyDataset("NASADEM Global DEM · 30 m", "NASADEM"),
        OpenTopographyDataset("SRTM · 30 m", "SRTMGL1"),
        OpenTopographyDataset("SRTM · 90 m", "SRTMGL3"),
        OpenTopographyDataset("SRTM GL1 Ellipsoidal · 30 m", "SRTMGL1_E")
    )

    fun download(apiKey: String, dataset: OpenTopographyDataset, extent: DemExtent, output: File) {
        require(apiKey.isNotBlank()) { "Enter your OpenTopography API key." }
        require(extent.south in -90.0..90.0 && extent.north in -90.0..90.0 &&
            extent.west in -180.0..180.0 && extent.east in -180.0..180.0) {
            "Extent coordinates must be within valid WGS84 ranges."
        }
        require(extent.south < extent.north && extent.west < extent.east) {
            "Invalid extent: south must be below north and west must be left of east."
        }
        if (dataset.code == "ANADEM") {
            val intersects = extent.west <= -34.72655 && extent.east >= -82.51655 &&
                extent.south <= 14.07948 && extent.north >= -56.51552
            require(intersects) { "ANADEM coverage is limited to South America." }
        }

        val params = linkedMapOf(
            "demtype" to dataset.code,
            "south" to extent.south.toString(),
            "north" to extent.north.toString(),
            "west" to extent.west.toString(),
            "east" to extent.east.toString(),
            "outputFormat" to "GTiff",
            "API_Key" to apiKey.trim()
        )
        val query = params.entries.joinToString("&") { (key, value) ->
            "${enc(key)}=${enc(value)}"
        }
        val connection = (URL("https://portal.opentopography.org/API/globaldem?$query").openConnection()
            as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("Accept", "image/tiff, application/octet-stream, */*")
            setRequestProperty("User-Agent", "aGIS-Android/1.0 OpenTopography DEM Downloader")
        }

        output.parentFile?.mkdirs()
        val partial = File(output.parentFile, output.name + ".part")
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException(extractServiceError(message).ifBlank {
                    "OpenTopography request failed (HTTP $status). Check the API key, dataset and extent."
                })
            }
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial).use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                    }
                    sink.fd.sync()
                }
            }
            require(partial.length() > 8L && isGeoTiff(partial)) {
                val response = partial.inputStream().bufferedReader().use { it.readText().take(1200) }
                extractServiceError(response).ifBlank {
                    "The server response is not a valid GeoTIFF. Check the API key and requested area."
                }
            }
            if (output.exists() && !output.delete()) error("Could not replace the existing output file.")
            require(partial.renameTo(output)) { "Could not finalize the downloaded GeoTIFF." }
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun isGeoTiff(file: File): Boolean {
        val bytes = ByteArray(4)
        file.inputStream().use { if (it.read(bytes) != 4) return false }
        return (bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()) ||
            (bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() &&
                bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte())
    }

    private fun extractServiceError(body: String): String {
        if (body.isBlank()) return ""
        val xml = Regex("<error[^>]*>(.*?)</error>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.groupValues?.getOrNull(1)?.trim()
        return (xml ?: body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim())
            .take(1000)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

@Composable
internal fun OpenTopographyDemDownloaderDialog(
    initialExtent: DemExtent?,
    outputDirectory: File,
    onDismiss: () -> Unit,
    onDownloaded: (File) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("opentopography_dem_downloader", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var selectedDataset by remember { mutableStateOf(OpenTopographyDemService.datasets.first { it.code == "SRTMGL1" }) }
    var datasetMenu by remember { mutableStateOf(false) }
    var south by remember { mutableStateOf(initialExtent?.south?.let { fmt(it) } ?: "") }
    var north by remember { mutableStateOf(initialExtent?.north?.let { fmt(it) } ?: "") }
    var west by remember { mutableStateOf(initialExtent?.west?.let { fmt(it) } ?: "") }
    var east by remember { mutableStateOf(initialExtent?.east?.let { fmt(it) } ?: "") }
    var outputName by remember { mutableStateOf("opentopography_dem.tif") }
    var downloading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("OpenTopography DEM Downloader") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Select a DEM dataset and define the download extent in WGS84 (EPSG:4326).",
                    style = MaterialTheme.typography.bodySmall)
                Text("DEM dataset", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { datasetMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedDataset.label)
                    }
                    DropdownMenu(expanded = datasetMenu, onDismissRequest = { datasetMenu = false }) {
                        OpenTopographyDemService.datasets.forEach { dataset ->
                            DropdownMenuItem(
                                text = { Text(dataset.label) },
                                onClick = { selectedDataset = dataset; datasetMenu = false }
                            )
                        }
                    }
                }
                Text("Download extent · EPSG:4326", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(south, { south = it }, label = { Text("South") },
                        modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(north, { north = it }, label = { Text("North") },
                        modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(west, { west = it }, label = { Text("West") },
                        modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(east, { east = it }, label = { Text("East") },
                        modifier = Modifier.weight(1f), singleLine = true)
                }
                if (initialExtent == null) {
                    Text("Map extent is unavailable. Enter coordinates manually or return to the map.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Values were initialized from the current map view. You can edit them.",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("OpenTopography API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = outputName, onValueChange = { outputName = it },
                    label = { Text("Output GeoTIFF filename") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text("Requires a free OpenTopography API key. Key is saved locally on this device.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Original QGIS plugin by Kyaw Naing Win · Native aGIS port",
                    style = MaterialTheme.typography.labelSmall)
                if (downloading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text("Downloading DEM…")
                    }
                }
                if (errorText.isNotBlank()) {
                    Text(errorText, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = !downloading, onClick = {
                val extent = DemExtent(
                    south.toDoubleOrNull() ?: Double.NaN,
                    north.toDoubleOrNull() ?: Double.NaN,
                    west.toDoubleOrNull() ?: Double.NaN,
                    east.toDoubleOrNull() ?: Double.NaN
                )
                val safeName = outputName.trim().let {
                    if (it.endsWith(".tif", true) || it.endsWith(".tiff", true)) it else "$it.tif"
                }
                if (apiKey.isBlank()) {
                    errorText = "Enter your OpenTopography API key."
                } else if (listOf(extent.south, extent.north, extent.west, extent.east).any { !it.isFinite() }) {
                    errorText = "Enter valid numeric values for all four extent coordinates."
                } else if (safeName.isBlank() || safeName.contains('/') || safeName.contains('\\')) {
                    errorText = "Enter a valid output filename."
                } else {
                    errorText = ""
                    downloading = true
                    prefs.edit().putString("api_key", apiKey.trim()).apply()
                    val target = File(outputDirectory, safeName)
                    Thread {
                        try {
                            OpenTopographyDemService.download(apiKey, selectedDataset, extent, target)
                            (context as? android.app.Activity)?.runOnUiThread {
                                downloading = false
                                Toast.makeText(context, "DEM downloaded: ${target.name}", Toast.LENGTH_LONG).show()
                                onDownloaded(target)
                            }
                        } catch (e: Exception) {
                            (context as? android.app.Activity)?.runOnUiThread {
                                downloading = false
                                errorText = e.localizedMessage ?: "DEM download failed."
                            }
                        }
                    }.start()
                }
            }) { Text(if (downloading) "Downloading…" else "Download DEM") }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun fmt(value: Double): String = String.format(Locale.US, "%.6f", value)
