package com.akib.geolite

import android.Manifest
import android.os.Bundle
import android.graphics.PointF
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.geojson.FeatureCollection
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

private const val DEFAULT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val OSM_STYLE_JSON = """{"version":8,"name":"OpenStreetMap","sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#f4f2ed"}},{"id":"osm","type":"raster","source":"osm","paint":{"raster-opacity":1}}]}"""
private const val IMAGERY_STYLE_JSON = """{"version":8,"name":"Esri World Imagery","sources":{"imagery":{"type":"raster","tiles":["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],"tileSize":256,"attribution":"Tiles © Esri — Sources: Esri, Maxar, Earthstar Geographics, and the GIS User Community"}},"layers":[{"id":"imagery","type":"raster","source":"imagery","paint":{"raster-opacity":1}}]}"""
private const val NO_BASEMAP_STYLE_JSON = """{"version":8,"name":"No basemap","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#e8edf0"}}]}"""
private data class Pin(val title: String, val lat: Double, val lon: Double)
private data class VectorOverlay(val id: String, val name: String, val geoJson: String, val featureCount: Long)
private data class RasterOverlay(val id: String, val imagePath: String, val west: Double, val north: Double, val east: Double, val south: Double)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(applicationContext)
        setContent { GeoLiteScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeoLiteScreen() {
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var selectedPanel by remember { mutableStateOf("Catalog") }
    var styleName by remember { mutableStateOf("OpenFreeMap · Liberty") }
    var styleMenu by remember { mutableStateOf(false) }
    var openMenu by remember { mutableStateOf(false) }
    var toolsMenu by remember { mutableStateOf(false) }
    var editMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready · Long-press the map to add a placemark") }
    var driverStatus by remember { mutableStateOf("Checking native GDAL drivers…") }
    var pins by remember { mutableStateOf(listOf<Pin>()) }
    var selectedFile by remember { mutableStateOf("No dataset loaded") }
    var vectorLayers by remember { mutableStateOf(listOf<String>()) }
    var vectorOverlays by remember { mutableStateOf(listOf<VectorOverlay>()) }
    var rasterOverlays by remember { mutableStateOf(listOf<RasterOverlay>()) }
    var mapTool by remember { mutableStateOf("Pan") }
    var digitizingVertices by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }
    var showStyleDialog by remember { mutableStateOf(false) }
    var showManualPinDialog by remember { mutableStateOf(false) }
    var showCreateLayerDialog by remember { mutableStateOf(false) }
    var showReprojectDialog by remember { mutableStateOf(false) }
    var showVectorReprojectDialog by remember { mutableStateOf(false) }
    var targetCrs by remember { mutableStateOf("EPSG:4326") }
    var showFieldDialog by remember { mutableStateOf(false) }
    var showAttributeEditDialog by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var editFieldName by remember { mutableStateOf("") }
    var newFieldType by remember { mutableStateOf("String") }
    var fieldTypeMenu by remember { mutableStateOf(false) }
    var editFieldValue by remember { mutableStateOf("") }
    var manualLat by remember { mutableStateOf("") }
    var manualLon by remember { mutableStateOf("") }
    var newLayerName by remember { mutableStateOf("new_points") }
    var newGeometryType by remember { mutableStateOf("Point") }
    var newStorageFormat by remember { mutableStateOf("Shapefile") }
    var storageFormatMenu by remember { mutableStateOf(false) }
    var geometryMenu by remember { mutableStateOf(false) }
    var selectedFeatureFid by remember { mutableStateOf<Long?>(null) }
    var selectedFeatureProperties by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var vectorColor by remember { mutableStateOf("#2878D0") }
    var markerIcon by remember { mutableStateOf("Pin") }
    var markerAnchor by remember { mutableStateOf("bottom") }
    var anchorMenu by remember { mutableStateOf(false) }
    var iconMenu by remember { mutableStateOf(false) }
    var vectorWidth by remember { mutableFloatStateOf(2.5f) }
    var pointSize by remember { mutableFloatStateOf(6f) }
    var fillOpacityValue by remember { mutableFloatStateOf(0.30f) }
    var rasterOpacityValue by remember { mutableFloatStateOf(0.88f) }
    var scalePosition by remember { mutableStateOf("Bottom right") }
    var scalePositionMenu by remember { mutableStateOf(false) }
    var scaleLabel by remember { mutableStateOf("—") }
    var showScale by remember { mutableStateOf(true) }
    var rasterLayers by remember { mutableStateOf(listOf<String>()) }
    var lastVectorPath by remember { mutableStateOf<String?>(null) }
    var lastVectorLayerIndex by remember { mutableIntStateOf(0) }
    var lastVectorSourceId by remember { mutableStateOf<String?>(null) }
    var lastRasterPath by remember { mutableStateOf<String?>(null) }
    var showRasterProcessDialog by remember { mutableStateOf(false) }
    var showRasterCalculatorDialog by remember { mutableStateOf(false) }
    var showMaskClipDialog by remember { mutableStateOf(false) }
    var rasterCalcExpression by remember { mutableStateOf("A * 2") }
    var rasterCalcSecondPath by remember { mutableStateOf("") }
    var rasterCalcBMenu by remember { mutableStateOf(false) }
    var rasterSourcePaths by remember { mutableStateOf(listOf<String>()) }
    var pendingMapExportBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rasterProcessMode by remember { mutableStateOf("Clip") }
    var clipMinX by remember { mutableStateOf("") }
    var clipMinY by remember { mutableStateOf("") }
    var clipMaxX by remember { mutableStateOf("") }
    var clipMaxY by remember { mutableStateOf("") }
    var pixelWidth by remember { mutableStateOf("30") }
    var pixelHeight by remember { mutableStateOf("30") }
    var resamplingMethod by remember { mutableStateOf("Bilinear") }
    var showBufferDialog by remember { mutableStateOf(false) }
    var bufferDistance by remember { mutableStateOf("100") }
    var processing by remember { mutableStateOf(false) }
    var pendingGdbPath by remember { mutableStateOf<String?>(null) }
    var pendingGdbLayers by remember { mutableStateOf(emptyList<GdalEngine.VectorLayerInfo>()) }
    var showGdbLayerDialog by remember { mutableStateOf(false) }
    var pendingShpFiles by remember { mutableStateOf(emptyList<File>()) }
    var pendingExportGdbPath by remember { mutableStateOf<String?>(null) }
    var showShpFileDialog by remember { mutableStateOf(false) }
    var attributeRows by remember { mutableStateOf(listOf<Map<String, String>>()) }
    var selectedAttributeIndex by remember { mutableIntStateOf(0) }
    val currentPinCount = rememberUpdatedState(pins.size)

    LaunchedEffect(Unit) {
        Thread {
            try {
                val version = GdalEngine.initialize()
                val shp = GdalEngine.hasVectorDriver("ESRI Shapefile")
                val gdb = GdalEngine.hasOpenFileGdbDriver()
                val gpx = GdalEngine.hasVectorDriver("GPX")
                val tiff = GdalEngine.hasRasterDriver("GTiff")
                activity.runOnUiThread {
                    driverStatus = "GDAL $version · SHP ${if (shp) "✓" else "—"} · FileGDB ${if (gdb) "✓" else "—"} · GPX ${if (gpx) "✓" else "—"} · TIFF ${if (tiff) "✓" else "—"}"
                }
            } catch (e: Exception) {
                activity.runOnUiThread { driverStatus = "GDAL unavailable: ${e.localizedMessage}" }
            }
        }.start()
    }

    fun runTerrainTool(operation: String) {
        val input = lastRasterPath
        if (input == null) { status = "Open an elevation GeoTIFF first."; return }
        processing = true
        Thread {
            try {
                val outputDir = activity.getExternalFilesDir("GeoLite/Processing") ?: activity.filesDir
                outputDir.mkdirs()
                val output = File(outputDir, "${operation.lowercase()}_${System.currentTimeMillis()}.tif")
                GdalEngine.terrainRaster(input, output.absolutePath, operation)
                activity.runOnUiThread {
                    processing = false
                    status = "$operation raster created: ${output.absolutePath}. Open it from Catalog to view."
                }
            } catch (e: Exception) {
                activity.runOnUiThread { processing = false; status = "$operation failed: ${e.localizedMessage}" }
            }
        }.start()
    }

    fun addVectorOverlayToStyle(style: Style, overlay: VectorOverlay) {
        style.addSource(GeoJsonSource(overlay.id, overlay.geoJson))
        style.addLayer(FillLayer("${overlay.id}_fill", overlay.id).withProperties(
            fillColor(vectorColor), fillOpacity(fillOpacityValue)
        ))
        style.addLayer(LineLayer("${overlay.id}_line", overlay.id).withProperties(
            lineColor(vectorColor), lineWidth(vectorWidth)
        ))
        val iconId = "geolite_icon_${markerIcon.lowercase()}"
        if (style.getImage(iconId) == null) style.addImage(iconId, createMapMarkerIcon(markerIcon, vectorColor))
        style.addLayer(SymbolLayer("${overlay.id}_symbol", overlay.id).withProperties(
            iconImage(iconId), iconSize(pointSize / 12f), iconAllowOverlap(true), iconAnchor(markerAnchor)
        ))
    }

    fun addRasterOverlayToStyle(style: Style, overlay: RasterOverlay) {
        val quad = LatLngQuad(
            LatLng(overlay.north, overlay.west), LatLng(overlay.north, overlay.east),
            LatLng(overlay.south, overlay.east), LatLng(overlay.south, overlay.west)
        )
        style.addSource(ImageSource(overlay.id, quad, File(overlay.imagePath).toURI()))
        style.addLayer(RasterLayer("${overlay.id}_layer", overlay.id).withProperties(rasterOpacity(rasterOpacityValue)))
    }

    fun changeBasemap(styleJson: String, name: String) {
        val activeMap = map ?: return
        activeMap.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            vectorOverlays.forEach { overlay -> addVectorOverlayToStyle(style, overlay) }
            rasterOverlays.forEach { overlay -> addRasterOverlayToStyle(style, overlay) }
            styleName = name
            status = "Basemap changed: $name"
        }
    }

    fun applyVectorStyle() {
        map?.getStyle { style ->
            vectorOverlays.forEach { overlay ->
                style.removeLayer("${overlay.id}_fill")
                style.removeLayer("${overlay.id}_line")
                style.removeLayer("${overlay.id}_point")
                style.removeLayer("${overlay.id}_symbol")
            }
            style.removeImage("geolite_icon_${markerIcon.lowercase()}")
            vectorOverlays.forEach { overlay -> addVectorOverlayToStyle(style, overlay) }
            rasterOverlays.forEach { overlay ->
                style.removeLayer("${overlay.id}_layer")
                style.addLayer(RasterLayer("${overlay.id}_layer", overlay.id).withProperties(rasterOpacity(rasterOpacityValue)))
            }
            status = "Layer symbology updated"
        }
    }

    fun refreshActiveVector() {
        val path = lastVectorPath ?: return
        val sourceId = lastVectorSourceId
        val layerIndex = lastVectorLayerIndex
        Thread {
            try {
                val result = GdalEngine.openVector(path, layerIndex)
                val features = JSONObject(result.geoJson).optJSONArray("features") ?: org.json.JSONArray()
                val rows = (0 until features.length()).map { i ->
                    val props = features.getJSONObject(i).optJSONObject("properties") ?: JSONObject()
                    val row = linkedMapOf<String, String>()
                    val keys = props.keys()
                    while (keys.hasNext()) { val key = keys.next(); row[key] = props.optString(key, "") }
                    row
                }
                activity.runOnUiThread {
                    attributeRows = rows
                    selectedAttributeIndex = 0
                    selectedFeatureFid = null
                    if (sourceId != null) map?.getStyle { style ->
                        (style.getSource(sourceId) as? GeoJsonSource)?.setGeoJson(result.geoJson)
                    }
                    vectorOverlays = vectorOverlays.map {
                        if (it.id == sourceId) it.copy(geoJson = result.geoJson, featureCount = result.featureCount) else it
                    }
                    status = "Layer refreshed · ${result.featureCount} features"
                }
            } catch (e: Exception) {
                activity.runOnUiThread { status = "Could not refresh layer: ${e.localizedMessage}" }
            }
        }.start()
    }

    fun addPin(pin: Pin) {
        pins = pins + pin
        map?.addMarker(MarkerOptions().position(LatLng(pin.lat, pin.lon)).title(pin.title)
            .snippet("%.6f, %.6f".format(pin.lat, pin.lon))
            .icon(IconFactory.getInstance(context).fromBitmap(createMapMarkerIcon(markerIcon, vectorColor))))
        status = "Placemark added: ${pin.title}"
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val displayName = queryDisplayName(context, uri)
            status = "Opening $displayName…"
            Thread {
                try {
                    val copied = materializeDocument(context, uri, displayName)
                    val extension = displayName.substringAfterLast('.', "").lowercase()
                    if (extension in listOf("tif", "tiff", "img", "vrt")) {
                        val info = GdalEngine.openRaster(copied.absolutePath)
                        val description = GdalEngine.describeRaster(info)
                        val png = File(context.cacheDir, "raster_preview_${System.currentTimeMillis()}.png")
                        val imageInfo = GdalEngine.createMapImage(copied.absolutePath, png.absolutePath)
                        val overlay = RasterOverlay("raster_${System.currentTimeMillis()}", png.absolutePath,
                            imageInfo.west, imageInfo.north, imageInfo.east, imageInfo.south)
                        activity.runOnUiThread {
                            lastRasterPath = copied.absolutePath
                            rasterSourcePaths = (rasterSourcePaths + copied.absolutePath).distinct()
                            rasterOverlays = rasterOverlays + overlay
                            selectedFile = displayName
                            rasterLayers = rasterLayers + "$displayName · $description"
                            map?.getStyle { style -> addRasterOverlayToStyle(style, overlay) }
                            status = "Raster opened and displayed: $description · ${info.projection.ifBlank { "CRS not defined" }}"
                            selectedPanel = "Layers"
                        }
                    } else if (extension == "gpx") {
                        require(GdalEngine.hasVectorDriver("GPX")) {
                            "GPX support is not present in the bundled GDAL runtime. Rebuild GDAL with OGR_ENABLE_DRIVER_GPX=ON."
                        }
                        val layers = GdalEngine.listVectorLayers(copied.absolutePath)
                        require(layers.isNotEmpty()) { "No GPX waypoints, routes or tracks were found." }
                        activity.runOnUiThread {
                            pendingGdbPath = copied.absolutePath
                            pendingGdbLayers = layers
                            showGdbLayerDialog = true
                            selectedFile = displayName
                            status = "GPX contains ${layers.size} layer(s). Choose waypoints, routes or tracks."
                            selectedPanel = "Layers"
                        }
                    } else {
                        if (extension == "shp") error("A Shapefile needs its .shx and .dbf sidecar files. Use Open SHP folder, or select a ZIP containing the full Shapefile set.")
                        val vectorFile = if (extension == "zip") extractShapefile(copied) else copied
                        val result = GdalEngine.openVector(vectorFile.absolutePath)
                        val document = JSONObject(result.geoJson)
                        val featureArray = document.optJSONArray("features") ?: org.json.JSONArray()
                        val parsedRows = (0 until featureArray.length()).map { index ->
                            val feature = featureArray.getJSONObject(index)
                            val properties = feature.optJSONObject("properties") ?: JSONObject()
                            val row = linkedMapOf<String, String>()
                            val keys = properties.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val value = properties.opt(key)
                                row[key] = if (value == null || value == JSONObject.NULL) "" else value.toString()
                            }
                            row
                        }
                        val sourceId = "gdal_vector_${System.currentTimeMillis()}"
                        val layerLabel = displayName.substringBeforeLast('.', displayName)
                        activity.runOnUiThread {
                            val activeMap = map
                            if (activeMap == null) {
                                status = "Map is not ready. Reopen the dataset after the map loads."
                            } else {
                                activeMap.getStyle { style ->
                                    try {
                                        val overlay = VectorOverlay(sourceId, layerLabel, result.geoJson, result.featureCount)
                                        addVectorOverlayToStyle(style, overlay)
                                        vectorLayers = vectorLayers + "$layerLabel · ${result.featureCount} features"
                                        vectorOverlays = vectorOverlays + overlay
                                        attributeRows = parsedRows
                                        selectedAttributeIndex = 0
                                        lastVectorPath = vectorFile.absolutePath
                                        lastVectorSourceId = sourceId
                                        lastVectorLayerIndex = 0
                                        selectedFile = displayName
                                        status = "Vector layer opened: $layerLabel · ${result.featureCount} features · GDAL ${GdalEngine.initialize()}"
                                        selectedPanel = "Layers"
                                    } catch (e: Exception) {
                                        status = "Could not render vector layer: ${e.localizedMessage}"
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        selectedFile = displayName
                        status = "Could not open $displayName: ${e.localizedMessage ?: "Unsupported dataset"}"
                        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    fun loadFileGdbLayer(gdbPath: String, layerInfo: GdalEngine.VectorLayerInfo) {
        processing = true
        showGdbLayerDialog = false
        status = "Loading dataset layer ${layerInfo.name}…"
        Thread {
            try {
                val result = GdalEngine.openVector(gdbPath, layerInfo.index)
                val featureArray = JSONObject(result.geoJson).optJSONArray("features") ?: org.json.JSONArray()
                val parsedRows = (0 until featureArray.length()).map { index ->
                    val properties = featureArray.getJSONObject(index).optJSONObject("properties") ?: JSONObject()
                    val row = linkedMapOf<String, String>()
                    val keys = properties.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = properties.opt(key)
                        row[key] = if (value == null || value == JSONObject.NULL) "" else value.toString()
                    }
                    row
                }
                val sourceId = "filegdb_vector_${System.currentTimeMillis()}"
                activity.runOnUiThread {
                    val activeMap = map
                    if (activeMap == null) {
                        status = "Feature class read, but the map is not ready. Try loading it again."
                        processing = false
                    } else {
                        activeMap.getStyle { style ->
                            try {
                                val overlay = VectorOverlay(sourceId, result.layerName, result.geoJson, result.featureCount)
                                addVectorOverlayToStyle(style, overlay)
                                vectorOverlays = vectorOverlays + overlay
                                vectorLayers = vectorLayers + "${result.layerName} · ${result.featureCount} features"
                                attributeRows = parsedRows
                                selectedAttributeIndex = 0
                                lastVectorPath = gdbPath
                                lastVectorSourceId = sourceId
                                lastVectorLayerIndex = layerInfo.index
                                selectedFile = File(gdbPath).name
                                status = "Loaded ${result.layerName}: ${result.featureCount} features"
                                selectedPanel = "Layers"
                            } catch (e: Exception) {
                                status = "Feature class read, but map rendering failed: ${e.localizedMessage}"
                            } finally {
                                processing = false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    status = "Could not load ${layerInfo.name}: ${e.localizedMessage}"
                    processing = false
                    Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    val fileGdbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            status = "Copying FileGDB folder…"
            processing = true
            Thread {
                try {
                    val gdbFolder = copyDocumentTree(context, treeUri)
                    require(gdbFolder.name.endsWith(".gdb", true)) {
                        "Select the .gdb folder itself, not its parent folder."
                    }
                    require(GdalEngine.hasOpenFileGdbDriver()) {
                        "This GDAL package does not include the OpenFileGDB driver. FileGDB support requires a GDAL build with OpenFileGDB enabled."
                    }
                    val layers = GdalEngine.listVectorLayers(gdbFolder.absolutePath)
                    require(layers.isNotEmpty()) { "No readable vector feature classes were found in this FileGDB." }
                    activity.runOnUiThread {
                        pendingGdbPath = gdbFolder.absolutePath
                        pendingGdbLayers = layers
                        showGdbLayerDialog = true
                        status = "Found ${layers.size} feature class(es). Choose one to add."
                        processing = false
                        selectedPanel = "Layers"
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        status = "FileGDB import failed: ${e.localizedMessage ?: "Could not read this geodatabase."}"
                        processing = false
                        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

        fun loadShapefile(file: File) {
        processing = true
        showShpFileDialog = false
        Thread {
            try {
                val result = GdalEngine.openVector(file.absolutePath)
                val featureArray = JSONObject(result.geoJson).optJSONArray("features") ?: org.json.JSONArray()
                val rows = (0 until featureArray.length()).map { index ->
                    val props = featureArray.getJSONObject(index).optJSONObject("properties") ?: JSONObject()
                    val row = linkedMapOf<String, String>()
                    val keys = props.keys()
                    while (keys.hasNext()) { val key = keys.next(); row[key] = props.optString(key, "") }
                    row
                }
                val id = "shp_${System.currentTimeMillis()}"
                activity.runOnUiThread {
                    map?.getStyle { style ->
                        try {
                            val overlay = VectorOverlay(id, file.nameWithoutExtension, result.geoJson, result.featureCount)
                            addVectorOverlayToStyle(style, overlay)
                            vectorOverlays = vectorOverlays + overlay
                            vectorLayers = vectorLayers + "${file.name} · ${result.featureCount} features"
                            attributeRows = rows
                            selectedAttributeIndex = 0
                            lastVectorPath = file.absolutePath
                            lastVectorSourceId = id
                            lastVectorLayerIndex = 0
                            selectedFile = file.name
                            selectedPanel = "Layers"
                            status = "Shapefile opened: ${file.name} · ${result.featureCount} features"
                        } catch (e: Exception) { status = "Shapefile loaded but map display failed: ${e.localizedMessage}" }
                    } ?: run { status = "Map is not ready. Try opening the Shapefile again." }
                    processing = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread { status = "Could not open Shapefile: ${e.localizedMessage}"; processing = false }
            }
        }.start()
    }

    val shpFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            processing = true
            Thread {
                try {
                    val copied = copyDirectoryTree(context, uri)
                    val files = copied.walkTopDown().filter { it.isFile && it.extension.equals("shp", true) }.toList()
                    require(files.isNotEmpty()) { "No .shp files found in the selected folder." }
                    activity.runOnUiThread {
                        pendingShpFiles = files
                        showShpFileDialog = true
                        status = "Found ${files.size} Shapefile(s). Choose one to open."
                        processing = false
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread { status = "Could not read Shapefile folder: ${e.localizedMessage}"; processing = false }
                }
            }.start()
        }
    }

    val exportGdbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val path = pendingExportGdbPath
        if (treeUri != null && path != null) {
            processing = true
            Thread {
                try {
                    copyLocalDirectoryToTree(context, File(path), treeUri)
                    activity.runOnUiThread {
                        processing = false
                        status = "Edited FileGDB exported to the selected folder."
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        processing = false
                        status = "FileGDB export failed: ${e.localizedMessage}"
                    }
                }
            }.start()
        }
    }

    val pngExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = pendingMapExportBitmap
        if (uri != null && bitmap != null) {
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                    require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encoding failed." }
                } ?: error("Could not open the selected output file.")
                status = "Map PNG exported successfully."
            } catch (e: Exception) { status = "PNG export failed: ${e.localizedMessage}" }
        }
        pendingMapExportBitmap = null
    }

    val pdfExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val bitmap = pendingMapExportBitmap
        if (uri != null && bitmap != null) {
            val document = PdfDocument()
            try {
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
                val page = document.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
                document.finishPage(page)
                context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                    document.writeTo(stream)
                } ?: error("Could not open the selected PDF file.")
                status = "Map PDF exported successfully."
            } catch (e: Exception) { status = "PDF export failed: ${e.localizedMessage}" }
            finally { document.close(); pendingMapExportBitmap = null }
        } else pendingMapExportBitmap = null
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val allowed = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (allowed) {
            val client = LocationServices.getFusedLocationProviderClient(context)
            try {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            val p = Pin("My location", location.latitude, location.longitude)
                            addPin(p)
                            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 15.0))
                        } else status = "Location unavailable. Check GPS and try again."
                    }
                    .addOnFailureListener { status = "Could not get location: ${it.localizedMessage}" }
            } catch (_: SecurityException) { status = "Location permission is required." }
        } else status = "Location permission denied."
    }

    fun beginMapExport(asPdf: Boolean) {
        val currentMap = map
        if (currentMap == null) {
            status = "Map is not ready yet."
            return
        }
        status = "Capturing map…"
        currentMap.snapshot { bitmap ->
            pendingMapExportBitmap = bitmap
            val name = "GeoLite_Map_${System.currentTimeMillis()}" + if (asPdf) ".pdf" else ".png"
            if (asPdf) pdfExportPicker.launch(name) else pngExportPicker.launch(name)
            status = "Choose where to save the map."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GeoLite", fontWeight = FontWeight.Bold)
                        Text("NATIVE GIS WORKSPACE", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { openMenu = true }) { Text("＋ Open ▾") }
                        DropdownMenu(expanded = openMenu, onDismissRequest = { openMenu = false }) {
                            DropdownMenuItem(text = { Text("Open vector / raster file") }, onClick = {
                                openMenu = false; filePicker.launch(arrayOf("*/*"))
                            })
                            DropdownMenuItem(text = { Text("Open Shapefile folder") }, onClick = {
                                openMenu = false; shpFolderPicker.launch(null)
                            })
                            DropdownMenuItem(text = { Text("Open FileGDB folder") }, onClick = {
                                openMenu = false; fileGdbPicker.launch(null)
                            })
                        }
                    }
                    Box {
                        TextButton(onClick = { toolsMenu = true }) { Text("Tools ▾") }
                        DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }) {
                            DropdownMenuItem(text = { Text("Export map as PNG") }, onClick = {
                                toolsMenu = false; beginMapExport(false)
                            })
                            DropdownMenuItem(text = { Text("Export map as PDF") }, onClick = {
                                toolsMenu = false; beginMapExport(true)
                            })
                            DropdownMenuItem(text = { Text("Raster calculator") }, onClick = {
                                toolsMenu = false
                                if (lastRasterPath == null) status = "Open a raster first."
                                else showRasterCalculatorDialog = true
                            })
                            DropdownMenuItem(text = { Text("Clip raster by polygon mask") }, onClick = {
                                toolsMenu = false
                                if (lastRasterPath == null || lastVectorPath == null) status = "Open a raster and a polygon mask layer first."
                                else showMaskClipDialog = true
                            })
                            DropdownMenuItem(text = { Text("Reproject raster") }, onClick = {
                                toolsMenu = false
                                if (lastRasterPath == null) status = "Open a TIFF raster first."
                                else showReprojectDialog = true
                            })
                            DropdownMenuItem(text = { Text("Reproject vector layer") }, onClick = {
                                toolsMenu = false
                                if (lastVectorPath == null) status = "Open a vector layer first."
                                else showVectorReprojectDialog = true
                            })
                            DropdownMenuItem(text = { Text("Vector buffer") }, onClick = {
                                toolsMenu = false; showBufferDialog = true
                            })
                            DropdownMenuItem(text = { Text("Terrain · Slope") }, onClick = { toolsMenu = false; runTerrainTool("Slope") })
                            DropdownMenuItem(text = { Text("Terrain · Aspect") }, onClick = { toolsMenu = false; runTerrainTool("Aspect") })
                            DropdownMenuItem(text = { Text("Terrain · Hillshade") }, onClick = { toolsMenu = false; runTerrainTool("Hillshade") })
                            DropdownMenuItem(text = { Text("Clip raster by extent") }, onClick = {
                                toolsMenu = false
                                if (lastRasterPath == null) status = "Open a raster first." else { rasterProcessMode = "Clip"; showRasterProcessDialog = true }
                            })
                            DropdownMenuItem(text = { Text("Resample raster") }, onClick = {
                                toolsMenu = false
                                if (lastRasterPath == null) status = "Open a raster first." else { rasterProcessMode = "Resample"; showRasterProcessDialog = true }
                            })
                        }
                    }
                    Box {
                        TextButton(onClick = { editMenu = true }) { Text("Edit ▾") }
                        DropdownMenu(expanded = editMenu, onDismissRequest = { editMenu = false }) {
                            DropdownMenuItem(text = { Text("Create feature class") }, onClick = {
                                editMenu = false; showCreateLayerDialog = true
                            })
                            DropdownMenuItem(text = { Text("Add field") }, onClick = {
                                editMenu = false; showFieldDialog = true
                            })
                            DropdownMenuItem(text = { Text("Edit selected attribute") }, onClick = {
                                editMenu = false; showAttributeEditDialog = true
                            })
                            DropdownMenuItem(text = { Text("Export edited FileGDB copy") }, onClick = {
                                editMenu = false
                                val path = lastVectorPath
                                if (path == null || !path.endsWith(".gdb", true)) {
                                    status = "Open or create a FileGDB first."
                                } else {
                                    pendingExportGdbPath = path
                                    exportGdbPicker.launch(null)
                                }
                            })
                            DropdownMenuItem(text = { Text("Edit selected line/polygon vertex") }, onClick = {
                                editMenu = false
                                if (selectedFeatureFid == null) status = "Select a line or polygon feature first."
                                else { mapTool = "Edit vertex"; status = "Tap near the vertex you want to move." }
                            })
                            DropdownMenuItem(text = { Text("Insert line/polygon vertex") }, onClick = {
                                editMenu = false
                                if (selectedFeatureFid == null) status = "Select a line or polygon feature first."
                                else { mapTool = "Insert vertex"; status = "Tap near the segment where the new vertex should be inserted." }
                            })
                            DropdownMenuItem(text = { Text("Delete line/polygon vertex") }, onClick = {
                                editMenu = false
                                if (selectedFeatureFid == null) status = "Select a line or polygon feature first."
                                else { mapTool = "Delete vertex"; status = "Tap near the vertex you want to delete." }
                            })
                            DropdownMenuItem(text = { Text("Move selected point") }, onClick = {
                                editMenu = false; mapTool = "Move point"; status = "Identify/select a point feature first, then tap its new position."
                            })
                            DropdownMenuItem(text = { Text("Delete selected feature") }, onClick = {
                                editMenu = false
                                val path = lastVectorPath
                                val fid = selectedFeatureFid
                                if (path == null || fid == null) status = "Select a feature on the map or in the attribute table first."
                                else {
                                    try {
                                        GdalEngine.deleteFeature(path, lastVectorLayerIndex, fid)
                                        refreshActiveVector()
                                        status = "Feature $fid deleted."
                                    } catch (e: Exception) { status = "Delete failed: ${e.localizedMessage}" }
                                }
                            })
                        }
                    }

                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 5.dp) {
                Text(status, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.width(210.dp).fillMaxHeight().background(Color(0xFFF1F3F5)).padding(10.dp)) {
                Text("WORKSPACE", style = MaterialTheme.typography.labelSmall, color = Color.DarkGray,
                    fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Catalog", "Layers", "Table").forEach { tab ->
                        FilterChip(selected = selectedPanel == tab, onClick = { selectedPanel = tab },
                            label = { Text(tab) })
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                if (selectedPanel == "Catalog") {
                    Text("PROJECT", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("GeoLite Native", modifier = Modifier.padding(vertical = 8.dp))
                    Text("DATA SOURCES", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("＋ Add data") }
                    Text(selectedFile, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                    Spacer(Modifier.height(12.dp))
                    Text("GDAL FORMAT DRIVERS", style = MaterialTheme.typography.labelSmall)
                    Text(driverStatus, style = MaterialTheme.typography.bodySmall)
                } else if (selectedPanel == "Layers") {
                    Text("MAP LAYERS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Box {
                        TextButton(onClick = { styleMenu = true }) { Text("Basemap ▾") }
                        DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                            DropdownMenuItem(text = { Text("OpenStreetMap") }, onClick = {
                                styleMenu = false; changeBasemap(OSM_STYLE_JSON, "OpenStreetMap")
                            })
                            DropdownMenuItem(text = { Text("Satellite imagery · Esri") }, onClick = {
                                styleMenu = false; changeBasemap(IMAGERY_STYLE_JSON, "Esri World Imagery")
                            })
                            DropdownMenuItem(text = { Text("OpenFreeMap · Liberty") }, onClick = {
                                styleMenu = false
                                map?.setStyle(DEFAULT_STYLE) { style ->
                                    vectorOverlays.forEach { addVectorOverlayToStyle(style, it) }
                                    rasterOverlays.forEach { addRasterOverlayToStyle(style, it) }
                                    styleName = "OpenFreeMap · Liberty"; status = "Basemap changed to Liberty"
                                }
                            })
                            DropdownMenuItem(text = { Text("OpenFreeMap · Positron") }, onClick = {
                                styleMenu = false
                                map?.setStyle("https://tiles.openfreemap.org/styles/positron") { style ->
                                    vectorOverlays.forEach { addVectorOverlayToStyle(style, it) }
                                    rasterOverlays.forEach { addRasterOverlayToStyle(style, it) }
                                    styleName = "OpenFreeMap · Positron"; status = "Basemap changed to Positron"
                                }
                            })
                            DropdownMenuItem(text = { Text("No basemap") }, onClick = {
                                styleMenu = false; changeBasemap(NO_BASEMAP_STYLE_JSON, "No basemap")
                            })
                        }
                    }
                    Text("☑  $styleName", modifier = Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                    Text("VECTOR LAYERS (${vectorLayers.size})", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium)
                    if (pendingGdbPath != null && pendingGdbLayers.isNotEmpty()) {
                        TextButton(onClick = { showGdbLayerDialog = true }, enabled = !processing) {
                            Text("＋ Add another dataset layer")
                        }
                    }
                    vectorLayers.forEach { layer ->
                        Text("▰  $layer", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 3.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("RASTER LAYERS (${rasterLayers.size})", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium)
                    rasterLayers.forEach { layer ->
                        Text("▧  $layer", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 3.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("PLACEMARKS (${pins.size})", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium)
                    LazyColumn {
                        items(pins) { pin ->
                            Text("●  ${pin.title}", style = MaterialTheme.typography.bodySmall)
                            Text("   %.5f, %.5f".format(pin.lat, pin.lon), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Text("ATTRIBUTE TABLE", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("${attributeRows.size} features · latest GeoJSON layer", style = MaterialTheme.typography.labelSmall,
                        color = Color.DarkGray, modifier = Modifier.padding(vertical = 6.dp))
                    if (attributeRows.isEmpty()) {
                        Text("Open a GeoJSON Feature or FeatureCollection to inspect its properties.",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn {
                            items(attributeRows.indices.toList()) { index ->
                                TextButton(onClick = {
                                        selectedAttributeIndex = index
                                        selectedFeatureFid = attributeRows.getOrNull(index)?.get("__fid")?.toLongOrNull()
                                        selectedFeatureProperties = attributeRows.getOrNull(index).orEmpty()
                                    },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(if (selectedAttributeIndex == index) "▶ Feature ${index + 1}" else "Feature ${index + 1}")
                                }
                            }
                        }
                        HorizontalDivider()
                        Text("FEATURE ${selectedAttributeIndex + 1}", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        val selectedRow = attributeRows.getOrNull(selectedAttributeIndex).orEmpty()
                        if (selectedRow.isEmpty()) Text("No properties for this feature.",
                            style = MaterialTheme.typography.bodySmall)
                        selectedRow.forEach { (field, value) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(field, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
                                    Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = {
                                    editFieldName = field
                                    editFieldValue = value
                                    showAttributeEditDialog = true
                                }) { Text("Edit") }
                            }
                            HorizontalDivider()
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { showFieldDialog = true }) { Text("＋ Field") }
                            TextButton(onClick = { showAttributeEditDialog = true }) { Text("Edit value") }
                            TextButton(onClick = {
                                val path = lastVectorPath
                                val fid = selectedFeatureFid
                                if (path == null || fid == null) status = "Select a feature first."
                                else try {
                                    GdalEngine.deleteFeature(path, lastVectorLayerIndex, fid)
                                    status = "Feature $fid deleted. Reopen the layer to refresh."
                                } catch (e: Exception) { status = "Delete failed: ${e.localizedMessage}" }
                            }) { Text("Delete feature") }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                HorizontalDivider()
                Text("TOOLS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION))
                }, modifier = Modifier.fillMaxWidth()) { Text("◎  My location") }
                TextButton(onClick = { status = "Long-press anywhere on the map to place a point." },
                    modifier = Modifier.fillMaxWidth()) { Text("⌖  Add placemark") }
                Text("Raster tools: Slope · Aspect · Hillshade · Clip · Resample. Open Tools menu to run processing.",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val view = mapView
                if (view == null) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                        MapView(ctx).also { mv ->
                            mv.onCreate(null)
                            mapView = mv
                            mv.getMapAsync { nativeMap ->
                                map = nativeMap
                                nativeMap.setStyle(DEFAULT_STYLE) {
                                    nativeMap.cameraPosition = CameraPosition.Builder()
                                        .target(LatLng(23.8103, 90.4125)).zoom(10.0).build()
                                    nativeMap.addOnMapLongClickListener { point ->
                                        addPin(Pin("Placemark ${currentPinCount.value + 1}", point.latitude, point.longitude))
                                        true
                                    }
                                    nativeMap.addOnMapClickListener { point ->
                                        when (mapTool) {
                                            "Placemark" -> {
                                                addPin(Pin("Placemark ${currentPinCount.value + 1}", point.latitude, point.longitude))
                                                mapTool = "Pan"
                                                true
                                            }
                                            "Add feature" -> {
                                                val path = lastVectorPath
                                                if (path == null) status = "Create or open an editable point Shapefile first."
                                                else try {
                                                    GdalEngine.createPointFeature(path, lastVectorLayerIndex, point.longitude, point.latitude)
                                                    refreshActiveVector()
                                                    status = "Point feature created at %.6f, %.6f.".format(point.latitude, point.longitude)
                                                } catch (e: Exception) { status = "Could not add feature: ${e.localizedMessage}" }
                                                true
                                            }
                                            "Add Line", "Add Polygon" -> {
                                                 digitizingVertices = digitizingVertices + (point.longitude to point.latitude)
                                                 status = "${mapTool.removePrefix("Add ")} vertex ${digitizingVertices.size} added · tap more vertices, then Finish."
                                                 true
                                             }
                                             "Edit vertex" -> {
                                                 val path = lastVectorPath
                                                 val fid = selectedFeatureFid
                                                 if (path == null || fid == null) status = "Select a line or polygon feature first."
                                                 else try {
                                                     val index = GdalEngine.moveNearestVertex(path, lastVectorLayerIndex, fid, point.longitude, point.latitude)
                                                     refreshActiveVector()
                                                     status = "Vertex ${index + 1} moved to %.6f, %.6f.".format(point.latitude, point.longitude)
                                                     mapTool = "Pan"
                                                 } catch (e: Exception) { status = "Vertex edit failed: ${e.localizedMessage}" }
                                                 true
                                             }
                                             "Insert vertex" -> {
                                                 val path = lastVectorPath
                                                 val fid = selectedFeatureFid
                                                 if (path == null || fid == null) status = "Select a line or polygon feature first."
                                                 else try {
                                                     val index = GdalEngine.insertNearestVertex(path, lastVectorLayerIndex, fid, point.longitude, point.latitude)
                                                     refreshActiveVector()
                                                     status = "Inserted vertex at position ${index + 1}."
                                                     mapTool = "Pan"
                                                 } catch (e: Exception) { status = "Vertex insertion failed: ${e.localizedMessage}" }
                                                 true
                                             }
                                             "Delete vertex" -> {
                                                 val path = lastVectorPath
                                                 val fid = selectedFeatureFid
                                                 if (path == null || fid == null) status = "Select a line or polygon feature first."
                                                 else try {
                                                     val index = GdalEngine.deleteNearestVertex(path, lastVectorLayerIndex, fid, point.longitude, point.latitude)
                                                     refreshActiveVector()
                                                     status = "Deleted vertex ${index + 1}."
                                                     mapTool = "Pan"
                                                 } catch (e: Exception) { status = "Vertex deletion failed: ${e.localizedMessage}" }
                                                 true
                                             }
                                             "Move point" -> {
                                                val path = lastVectorPath
                                                val fid = selectedFeatureFid
                                                if (path == null || fid == null) status = "Identify/select a point feature first."
                                                else try {
                                                    GdalEngine.movePointFeature(path, lastVectorLayerIndex, fid, point.longitude, point.latitude)
                                                    refreshActiveVector()
                                                    status = "Moved point feature $fid to %.6f, %.6f.".format(point.latitude, point.longitude)
                                                    mapTool = "Pan"
                                                } catch (e: Exception) { status = "Could not move feature: ${e.localizedMessage}" }
                                                true
                                            }
                                            "Identify", "Select" -> {
                                                val screenPoint = nativeMap.projection.toScreenLocation(point)
                                                val queryLayers = vectorOverlays.flatMap {
                                                    listOf("${it.id}_fill", "${it.id}_line", "${it.id}_symbol")
                                                }.toTypedArray()
                                                val found = nativeMap.queryRenderedFeatures(screenPoint, *queryLayers).firstOrNull()
                                                if (found != null) {
                                                    val props = JSONObject(found.toJson()).optJSONObject("properties")
                                                    val values = linkedMapOf<String, String>()
                                                    if (props != null) {
                                                        val keys = props.keys()
                                                        while (keys.hasNext()) {
                                                            val key = keys.next()
                                                            values[key] = props.opt(key)?.toString()?.takeUnless { it == "null" } ?: ""
                                                        }
                                                    }
                                                    selectedFeatureFid = props?.optString("__fid")?.toLongOrNull()
                                                    selectedFeatureProperties = values
                                                    val rowIndex = selectedFeatureFid?.let { fid ->
                                                        attributeRows.indexOfFirst { it["__fid"] == fid.toString() }
                                                    } ?: -1
                                                    if (rowIndex >= 0) selectedAttributeIndex = rowIndex
                                                    else {
                                                        attributeRows = attributeRows + values
                                                        selectedAttributeIndex = attributeRows.lastIndex
                                                    }
                                                    selectedPanel = "Table"
                                                    status = "${mapTool}: feature ${selectedFeatureFid ?: "selected"} · ${values.size} attributes"
                                                    if (mapTool == "Select") {
                                                        nativeMap.getStyle { style ->
                                                            style.removeLayer("selection_fill")
                                                            style.removeLayer("selection_line")
                                                            style.removeLayer("selection_point")
                                                            style.removeSource("selection_source")
                                                            style.addSource(GeoJsonSource("selection_source",
                                                                FeatureCollection.fromFeatures(listOf(found))))
                                                            style.addLayer(FillLayer("selection_fill", "selection_source").withProperties(
                                                                fillColor("#FF3B30"), fillOpacity(0.55f)))
                                                            style.addLayer(LineLayer("selection_line", "selection_source").withProperties(
                                                                lineColor("#FF3B30"), lineWidth(4.0f)))
                                                            style.addLayer(CircleLayer("selection_point", "selection_source").withProperties(
                                                                circleColor("#FF3B30"), circleRadius(9.0f)))
                                                        }
                                                    }
                                                } else status = "No vector feature found at this location."
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    nativeMap.addOnCameraIdleListener {
                                        val centerLat = nativeMap.cameraPosition.target?.latitude ?: 0.0
                                        val meters = nativeMap.projection.getMetersPerPixelAtLatitude(centerLat) * 100.0 * context.resources.displayMetrics.density
                                        scaleLabel = if (meters >= 1000) "${"%.2f".format(meters / 1000)} km / 100 px"
                                            else "${"%.0f".format(meters)} m / 100 px"
                                    }
                                    status = "Map ready · Tap Add placemark then tap the map, or long-press"
                                }
                            }
                        }
                    })
                } else {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { view })
                }
                Column(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .background(Color.White.copy(alpha = 0.94f), MaterialTheme.shapes.medium).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { mapTool = "Placemark"; status = "Tap the map to place a point." }) { Text("＋ Point") }
                        TextButton(onClick = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("◎ GPS") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = mapTool == "Identify", onClick = { mapTool = "Identify"; status = "Tap a feature to identify it." }, label = { Text("Identify") })
                        FilterChip(selected = mapTool == "Select", onClick = { mapTool = "Select"; status = "Tap a feature to select it." }, label = { Text("Select") })
                        FilterChip(selected = mapTool == "Pan", onClick = { mapTool = "Pan"; status = "Pan/zoom mode." }, label = { Text("Pan") })
                        FilterChip(selected = mapTool == "Add feature", onClick = { mapTool = "Add feature"; status = "Tap the map to create a point in the active Shapefile." }, label = { Text("Edit point") })
                        FilterChip(selected = mapTool == "Move point", onClick = { mapTool = "Move point"; status = "Identify/select a point feature first, then tap its new location." }, label = { Text("Move point") })
                         FilterChip(selected = mapTool == "Add Line", onClick = { digitizingVertices = emptyList(); mapTool = "Add Line"; status = "Tap the map to add line vertices. Finish after at least 2." }, label = { Text("Draw line") })
                         FilterChip(selected = mapTool == "Add Polygon", onClick = { digitizingVertices = emptyList(); mapTool = "Add Polygon"; status = "Tap the map to add polygon vertices. Finish after at least 3." }, label = { Text("Draw polygon") })
                         FilterChip(selected = mapTool == "Edit vertex", onClick = { mapTool = "Edit vertex"; status = "Select a line/polygon, then tap near a vertex to move it." }, label = { Text("Edit vertex") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { showManualPinDialog = true }) { Text("Coordinates") }
                        TextButton(onClick = { showStyleDialog = true }) { Text("Layer style") }
                    }
                }
                if (mapTool == "Add Line" || mapTool == "Add Polygon") {
                     Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp), color = Color.White,
                         shape = MaterialTheme.shapes.medium, shadowElevation = 4.dp) {
                         Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                             horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                             Text("${digitizingVertices.size} vertices", style = MaterialTheme.typography.labelMedium)
                             TextButton(onClick = { digitizingVertices = digitizingVertices.dropLast(1) }, enabled = digitizingVertices.isNotEmpty()) { Text("Undo") }
                             Button(onClick = {
                                 val path = lastVectorPath
                                 val needed = if (mapTool == "Add Polygon") 3 else 2
                                 if (path == null) status = "Create or open an editable line/polygon layer first."
                                 else if (digitizingVertices.size < needed) status = "Add at least $needed vertices before finishing."
                                 else {
                                     val type = if (mapTool == "Add Polygon") "Polygon" else "Line"
                                     try {
                                         GdalEngine.createGeometryFeature(path, lastVectorLayerIndex, type, digitizingVertices)
                                         digitizingVertices = emptyList(); mapTool = "Pan"; refreshActiveVector()
                                         status = "$type feature saved."
                                     } catch (e: Exception) { status = "Could not save $type: ${e.localizedMessage}" }
                                 }
                             }) { Text("Finish") }
                             TextButton(onClick = { digitizingVertices = emptyList(); mapTool = "Pan"; status = "Digitizing cancelled." }) { Text("Cancel") }
                         }
                     }
                 }
                 if (showScale) {
                    val scaleAlignment = when (scalePosition) {
                        "Top left" -> Alignment.TopStart
                        "Top right" -> Alignment.TopEnd
                        "Bottom left" -> Alignment.BottomStart
                        else -> Alignment.BottomEnd
                    }
                    Surface(modifier = Modifier.align(scaleAlignment).padding(12.dp), color = Color.White.copy(alpha = 0.92f),
                        shape = MaterialTheme.shapes.small, shadowElevation = 2.dp) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Text(scaleLabel, style = MaterialTheme.typography.labelSmall)
                            Box(Modifier.width(100.dp).height(3.dp).background(Color.Black))
                        }
                    }
                }
            }
        }
    }


    if (showVectorReprojectDialog) {
        AlertDialog(
            onDismissRequest = { showVectorReprojectDialog = false },
            title = { Text("Vector layer reprojection") },
            text = {
                Column {
                    Text("Input: ${lastVectorPath?.let { File(it).name } ?: "No vector"}")
                    OutlinedTextField(value = targetCrs, onValueChange = { targetCrs = it },
                        label = { Text("Target CRS (e.g. EPSG:32646)") }, singleLine = true)
                    Text("Creates a new Shapefile in GeoLite/Processing.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val input = lastVectorPath
                    if (input == null || targetCrs.isBlank()) status = "Select a vector layer and target CRS."
                    else {
                        showVectorReprojectDialog = false
                        processing = true
                        Thread {
                            try {
                                val outputDir = activity.getExternalFilesDir("GeoLite/Processing") ?: activity.filesDir
                                outputDir.mkdirs()
                                val output = File(outputDir, "reprojected_vector_${System.currentTimeMillis()}.shp")
                                val count = GdalEngine.reprojectVector(input, lastVectorLayerIndex, output.absolutePath, targetCrs.trim())
                                activity.runOnUiThread {
                                    processing = false
                                    status = "Reprojected $count vector feature(s): ${output.absolutePath}"
                                    loadShapefile(output)
                                }
                            } catch (e: Exception) {
                                activity.runOnUiThread { processing = false; status = "Vector reprojection failed: ${e.localizedMessage}" }
                            }
                        }.start()
                    }
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { showVectorReprojectDialog = false }) { Text("Cancel") } }
        )
    }

    if (showRasterCalculatorDialog) {
        AlertDialog(
            onDismissRequest = { showRasterCalculatorDialog = false },
            title = { Text("Raster calculator") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A = ${lastRasterPath?.let { File(it).name } ?: "No raster"}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("B = ")
                        Box {
                            TextButton(onClick = { rasterCalcBMenu = true }) {
                                Text(if (rasterCalcSecondPath.isBlank()) "No second raster" else File(rasterCalcSecondPath).name)
                            }
                            DropdownMenu(expanded = rasterCalcBMenu, onDismissRequest = { rasterCalcBMenu = false }) {
                                DropdownMenuItem(text = { Text("No second raster") }, onClick = {
                                    rasterCalcSecondPath = ""; rasterCalcBMenu = false
                                })
                                rasterSourcePaths.filter { it != lastRasterPath }.forEach { path ->
                                    DropdownMenuItem(text = { Text(File(path).name) }, onClick = {
                                        rasterCalcSecondPath = path; rasterCalcBMenu = false
                                    })
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = rasterCalcExpression,
                        onValueChange = { rasterCalcExpression = it },
                        label = { Text("Expression") },
                        placeholder = { Text("A * 2 + B") },
                        singleLine = true
                    )
                    Text("Operators: +  −  ×  ÷  ^  ( ). Use A and optional B.", style = MaterialTheme.typography.bodySmall)
                    Text("For two rasters, dimensions, CRS and pixel alignment must match.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val input = lastRasterPath
                    if (input == null || rasterCalcExpression.isBlank()) status = "Choose an input raster and enter an expression."
                    else {
                        showRasterCalculatorDialog = false
                        processing = true
                        Thread {
                            try {
                                val dir = activity.getExternalFilesDir("GeoLite/Processing") ?: activity.filesDir
                                dir.mkdirs()
                                val output = File(dir, "raster_calc_${System.currentTimeMillis()}.tif")
                                GdalEngine.rasterCalculator(input, rasterCalcSecondPath.ifBlank { null }, rasterCalcExpression, output.absolutePath)
                                activity.runOnUiThread {
                                    processing = false
                                    rasterSourcePaths = (rasterSourcePaths + output.absolutePath).distinct()
                                    status = "Raster calculation saved: ${output.absolutePath}. Open it from Catalog to view."
                                }
                            } catch (e: Exception) {
                                activity.runOnUiThread { processing = false; status = "Raster calculator failed: ${e.localizedMessage}" }
                            }
                        }.start()
                    }
                }) { Text("Run calculation") }
            },
            dismissButton = { TextButton(onClick = { showRasterCalculatorDialog = false }) { Text("Cancel") } }
        )
    }

    if (showMaskClipDialog) {
        AlertDialog(
            onDismissRequest = { showMaskClipDialog = false },
            title = { Text("Clip raster by polygon mask") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Raster: ${lastRasterPath?.let { File(it).name } ?: "None"}")
                    Text("Mask: ${lastVectorPath?.let { File(it).name } ?: "None"}")
                    Text("The polygon layer will be used as a cutline. Output outside the mask will be transparent.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val input = lastRasterPath
                    val mask = lastVectorPath
                    if (input == null || mask == null) status = "Open a raster and polygon mask first."
                    else {
                        showMaskClipDialog = false
                        processing = true
                        Thread {
                            try {
                                val dir = activity.getExternalFilesDir("GeoLite/Processing") ?: activity.filesDir
                                dir.mkdirs()
                                val output = File(dir, "mask_clip_${System.currentTimeMillis()}.tif")
                                GdalEngine.clipRasterByMask(input, mask, output.absolutePath, lastVectorLayerIndex)
                                activity.runOnUiThread {
                                    processing = false
                                    rasterSourcePaths = (rasterSourcePaths + output.absolutePath).distinct()
                                    status = "Mask-clipped raster saved: ${output.absolutePath}. Open it from Catalog to view."
                                }
                            } catch (e: Exception) {
                                activity.runOnUiThread { processing = false; status = "Mask clipping failed: ${e.localizedMessage}" }
                            }
                        }.start()
                    }
                }) { Text("Clip raster") }
            },
            dismissButton = { TextButton(onClick = { showMaskClipDialog = false }) { Text("Cancel") } }
        )
    }

    if (showRasterProcessDialog) {
        AlertDialog(
            onDismissRequest = { showRasterProcessDialog = false },
            title = { Text(if (rasterProcessMode == "Clip") "Clip raster by extent" else "Raster resampling") },
            text = {
                Column {
                    Text("Input: ${lastRasterPath?.let { File(it).name } ?: "No raster"}")
                    if (rasterProcessMode == "Clip") {
                        Text("Extent coordinates must use the input raster's CRS.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = clipMinX, onValueChange = { clipMinX = it }, label = { Text("Minimum X") }, singleLine = true)
                        OutlinedTextField(value = clipMinY, onValueChange = { clipMinY = it }, label = { Text("Minimum Y") }, singleLine = true)
                        OutlinedTextField(value = clipMaxX, onValueChange = { clipMaxX = it }, label = { Text("Maximum X") }, singleLine = true)
                        OutlinedTextField(value = clipMaxY, onValueChange = { clipMaxY = it }, label = { Text("Maximum Y") }, singleLine = true)
                    } else {
                        OutlinedTextField(value = pixelWidth, onValueChange = { pixelWidth = it }, label = { Text("Pixel width (map units)") }, singleLine = true)
                        OutlinedTextField(value = pixelHeight, onValueChange = { pixelHeight = it }, label = { Text("Pixel height (map units)") }, singleLine = true)
                        Text("Resampling: $resamplingMethod")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Nearest", "Bilinear", "Cubic", "Average").forEach { method ->
                                FilterChip(selected = resamplingMethod == method, onClick = { resamplingMethod = method }, label = { Text(method) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val input = lastRasterPath
                    val values = if (rasterProcessMode == "Clip") listOf(clipMinX, clipMinY, clipMaxX, clipMaxY).mapNotNull { it.toDoubleOrNull() }
                        else listOf(pixelWidth, pixelHeight).mapNotNull { it.toDoubleOrNull() }
                    val required = if (rasterProcessMode == "Clip") 4 else 2
                    if (input == null || values.size != required) {
                        status = "Enter valid numeric values for all parameters."
                    } else {
                        showRasterProcessDialog = false
                        processing = true
                        Thread {
                            try {
                                val dir = activity.getExternalFilesDir("GeoLite/Processing") ?: activity.filesDir
                                dir.mkdirs()
                                val output = File(dir, "${rasterProcessMode.lowercase()}_${System.currentTimeMillis()}.tif")
                                if (rasterProcessMode == "Clip") GdalEngine.clipRasterByExtent(input, output.absolutePath, values[0], values[1], values[2], values[3])
                                else GdalEngine.resampleRaster(input, output.absolutePath, values[0], values[1], resamplingMethod)
                                activity.runOnUiThread {
                                    processing = false
                                    status = "$rasterProcessMode raster created: ${output.absolutePath}. Open it from Catalog to view."
                                }
                            } catch (e: Exception) {
                                activity.runOnUiThread { processing = false; status = "$rasterProcessMode failed: ${e.localizedMessage}" }
                            }
                        }.start()
                    }
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { showRasterProcessDialog = false }) { Text("Cancel") } }
        )
    }

    if (showReprojectDialog) {
        AlertDialog(
            onDismissRequest = { showReprojectDialog = false },
            title = { Text("Raster reprojection") },
            text = {
                Column {
                    Text("Input: ${lastRasterPath?.let { File(it).name } ?: "No raster"}")
                    OutlinedTextField(value = targetCrs, onValueChange = { targetCrs = it },
                        label = { Text("Target CRS (e.g. EPSG:32646)") }, singleLine = true)
                    Text("Output will be saved as GeoTIFF in GeoLite/Processing.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val input = lastRasterPath
                    if (input == null || targetCrs.isBlank()) status = "Select an input raster and valid target CRS."
                    else {
                        showReprojectDialog = false
                        processing = true
                        Thread {
                            try {
                                val outputDir = activity.getExternalFilesDir("GeoLite/Processing") ?: activity.filesDir
                                outputDir.mkdirs()
                                val output = File(outputDir, "reprojected_${System.currentTimeMillis()}.tif")
                                GdalEngine.reprojectRaster(input, output.absolutePath, targetCrs.trim())
                                activity.runOnUiThread {
                                    processing = false
                                    status = "Reprojected raster saved: ${output.absolutePath}"
                                }
                            } catch (e: Exception) {
                                activity.runOnUiThread { processing = false; status = "Reprojection failed: ${e.localizedMessage}" }
                            }
                        }.start()
                    }
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { showReprojectDialog = false }) { Text("Cancel") } }
        )
    }

    if (showManualPinDialog) {
        AlertDialog(
            onDismissRequest = { showManualPinDialog = false },
            title = { Text("Add placemark by coordinates") },
            text = {
                Column {
                    OutlinedTextField(value = manualLat, onValueChange = { manualLat = it }, label = { Text("Latitude") }, singleLine = true)
                    OutlinedTextField(value = manualLon, onValueChange = { manualLon = it }, label = { Text("Longitude") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val lat = manualLat.toDoubleOrNull()
                    val lon = manualLon.toDoubleOrNull()
                    if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                        status = "Enter valid WGS84 latitude and longitude."
                    } else {
                        addPin(Pin("Manual placemark ${pins.size + 1}", lat, lon))
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15.0))
                        showManualPinDialog = false
                    }
                }) { Text("Add point") }
            },
            dismissButton = { TextButton(onClick = { showManualPinDialog = false }) { Text("Cancel") } }
        )
    }

    if (showStyleDialog) {
        AlertDialog(
            onDismissRequest = { showStyleDialog = false },
            title = { Text("Map element properties") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Vector color (hex)")
                    OutlinedTextField(value = vectorColor, onValueChange = { vectorColor = it }, singleLine = true)
                    Text("Line width · ${"%.1f".format(vectorWidth)} px")
                    Slider(value = vectorWidth, onValueChange = { vectorWidth = it }, valueRange = 0.5f..10f)
                    Text("Point icon")
                    Box {
                        TextButton(onClick = { iconMenu = true }) { Text("$markerIcon ▾") }
                        DropdownMenu(expanded = iconMenu, onDismissRequest = { iconMenu = false }) {
                            listOf("Pin", "Circle", "Star", "Square").forEach { shape ->
                                DropdownMenuItem(text = { Text(shape) }, onClick = { markerIcon = shape; iconMenu = false })
                            }
                        }
                    }
                    Text("Icon position / anchor")
                    Box {
                        TextButton(onClick = { anchorMenu = true }) { Text("$markerAnchor ▾") }
                        DropdownMenu(expanded = anchorMenu, onDismissRequest = { anchorMenu = false }) {
                            listOf("center", "bottom", "top", "left", "right").forEach { anchor ->
                                DropdownMenuItem(text = { Text(anchor) }, onClick = { markerAnchor = anchor; anchorMenu = false })
                            }
                        }
                    }
                    Text("Point size · ${"%.1f".format(pointSize)} px")
                    Slider(value = pointSize, onValueChange = { pointSize = it }, valueRange = 2f..20f)
                    Text("Polygon opacity · ${"%.0f".format(fillOpacityValue * 100)}%")
                    Slider(value = fillOpacityValue, onValueChange = { fillOpacityValue = it }, valueRange = 0.05f..1f)
                    Text("Raster opacity · ${"%.0f".format(rasterOpacityValue * 100)}%")
                    Slider(value = rasterOpacityValue, onValueChange = { rasterOpacityValue = it }, valueRange = 0.1f..1f)
                    Text("Scale division position")
                    Box {
                        TextButton(onClick = { scalePositionMenu = true }) { Text("$scalePosition ▾") }
                        DropdownMenu(expanded = scalePositionMenu, onDismissRequest = { scalePositionMenu = false }) {
                            listOf("Top left", "Top right", "Bottom left", "Bottom right").forEach { position ->
                                DropdownMenuItem(text = { Text(position) }, onClick = { scalePosition = position; scalePositionMenu = false })
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Show scale division", modifier = Modifier.weight(1f))
                        Switch(checked = showScale, onCheckedChange = { showScale = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (vectorColor.matches(Regex("#[0-9A-Fa-f]{6}"))) {
                        applyVectorStyle()
                        showStyleDialog = false
                    } else status = "Use a valid six-digit hex color, e.g. #2878D0."
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showStyleDialog = false }) { Text("Cancel") } }
        )
    }

    if (showCreateLayerDialog) {
        AlertDialog(
            onDismissRequest = { showCreateLayerDialog = false },
            title = { Text("Create feature class") },
            text = {
                Column {
                    OutlinedTextField(value = newLayerName, onValueChange = { newLayerName = it },
                        label = { Text("Feature class name") }, singleLine = true)
                    Box {
                        TextButton(onClick = { storageFormatMenu = true }) { Text("Format: $newStorageFormat ▾") }
                        DropdownMenu(expanded = storageFormatMenu, onDismissRequest = { storageFormatMenu = false }) {
                            listOf("Shapefile", "FileGDB").forEach { format ->
                                DropdownMenuItem(text = { Text(format) }, onClick = { newStorageFormat = format; storageFormatMenu = false })
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { geometryMenu = true }) { Text("Geometry: $newGeometryType ▾") }
                        DropdownMenu(expanded = geometryMenu, onDismissRequest = { geometryMenu = false }) {
                            listOf("Point", "Line", "Polygon").forEach { type ->
                                DropdownMenuItem(text = { Text(type) }, onClick = { newGeometryType = type; geometryMenu = false })
                            }
                        }
                    }
                    Text("Creates a WGS84 feature class. Use Edit point, Draw line or Draw polygon on the map to digitize geometry; vertex editing is available for selected lines and polygons.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val maxNameLength = if (newStorageFormat == "FileGDB") 64 else 10
                    val clean = newLayerName.replace(Regex("[^A-Za-z0-9_]"), "_").take(maxNameLength).ifBlank { "points" }
                    try {
                        val folder = activity.getExternalFilesDir("GeoLite/Projects") ?: activity.filesDir
                        folder.mkdirs()
                        val activeGdb = lastVectorPath?.takeIf { it.endsWith(".gdb", true) }
                        val path = if (newStorageFormat == "FileGDB") {
                            activeGdb ?: File(folder, "$clean.gdb").absolutePath
                        } else File(folder, "$clean.shp").absolutePath
                        val layerIndex = if (newStorageFormat == "FileGDB") {
                            GdalEngine.createFileGdbFeatureClass(path, clean, newGeometryType)
                        } else {
                            GdalEngine.createShapefile(path, clean, newGeometryType)
                            0
                        }
                        val result = GdalEngine.openVector(path, layerIndex)
                        val sourceId = "created_layer_${System.currentTimeMillis()}"
                        val overlay = VectorOverlay(sourceId, clean, result.geoJson, result.featureCount)
                        lastVectorPath = path
                        lastVectorLayerIndex = layerIndex
                        lastVectorSourceId = sourceId
                        if (newStorageFormat == "FileGDB") {
                            pendingGdbPath = path
                            pendingGdbLayers = GdalEngine.listVectorLayers(path)
                        }
                        vectorOverlays = vectorOverlays + overlay
                        vectorLayers = vectorLayers + "$clean · 0 features ($newStorageFormat)"
                        attributeRows = emptyList()
                        map?.getStyle { style -> addVectorOverlayToStyle(style, overlay) }
                        selectedFile = File(path).name
                        status = "Created editable WGS84 $newGeometryType $newStorageFormat feature class: $path"
                        showCreateLayerDialog = false
                    } catch (e: Exception) { status = "Create feature class failed: ${e.localizedMessage}" }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateLayerDialog = false }) { Text("Cancel") } }
        )
    }

    if (showFieldDialog) {
        AlertDialog(
            onDismissRequest = { showFieldDialog = false },
            title = { Text("Add attribute field") },
            text = {
                Column {
                    Text("Shapefile field names: max 10 characters.")
                    OutlinedTextField(value = editFieldName, onValueChange = { editFieldName = it },
                        label = { Text("Field name") }, singleLine = true)
                    Box {
                        TextButton(onClick = { fieldTypeMenu = true }) { Text("Type: $newFieldType ▾") }
                        DropdownMenu(expanded = fieldTypeMenu, onDismissRequest = { fieldTypeMenu = false }) {
                            listOf("String", "Integer", "Real", "Date").forEach { type ->
                                DropdownMenuItem(text = { Text(type) }, onClick = { newFieldType = type; fieldTypeMenu = false })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val path = lastVectorPath
                    if (path == null) status = "Open or create an editable Shapefile first."
                    else try {
                        GdalEngine.addField(path, lastVectorLayerIndex, editFieldName, newFieldType)
                        refreshActiveVector()
                        status = "Field $editFieldName added."
                        showFieldDialog = false
                    } catch (e: Exception) { status = "Add field failed: ${e.localizedMessage}" }
                }) { Text("Add field") }
            },
            dismissButton = { TextButton(onClick = { showFieldDialog = false }) { Text("Cancel") } }
        )
    }

    if (showAttributeEditDialog) {
        AlertDialog(
            onDismissRequest = { showAttributeEditDialog = false },
            title = { Text("Populate selected attribute") },
            text = {
                Column {
                    Text("Feature ID: ${selectedFeatureFid ?: "not selected"}")
                    OutlinedTextField(value = editFieldName, onValueChange = { editFieldName = it },
                        label = { Text("Field name") }, singleLine = true)
                    OutlinedTextField(value = editFieldValue, onValueChange = { editFieldValue = it },
                        label = { Text("New value") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val path = lastVectorPath
                    val fid = selectedFeatureFid
                    if (path == null || fid == null) status = "Select a feature and open an editable Shapefile."
                    else try {
                        GdalEngine.updateAttribute(path, lastVectorLayerIndex, fid, editFieldName, editFieldValue)
                        refreshActiveVector()
                        status = "Updated $editFieldName for feature $fid."
                        showAttributeEditDialog = false
                    } catch (e: Exception) { status = "Attribute update failed: ${e.localizedMessage}" }
                }) { Text("Save attribute") }
            },
            dismissButton = { TextButton(onClick = { showAttributeEditDialog = false }) { Text("Cancel") } }
        )
    }

    if (showShpFileDialog) {
        AlertDialog(
            onDismissRequest = { showShpFileDialog = false },
            title = { Text("Choose Shapefile") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(pendingShpFiles) { file ->
                        TextButton(onClick = { loadShapefile(file) }, modifier = Modifier.fillMaxWidth()) {
                            Text(file.name)
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showShpFileDialog = false }) { Text("Close") } }
        )
    }

    if (showGdbLayerDialog) {
        AlertDialog(
            onDismissRequest = { showGdbLayerDialog = false },
            title = { Text("Dataset layers") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(pendingGdbLayers) { layer ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(layer.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (layer.featureCount >= 0) "${layer.featureCount} features" else "Feature count unavailable",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                            TextButton(
                                onClick = {
                                    val path = pendingGdbPath
                                    if (path != null) loadFileGdbLayer(path, layer)
                                },
                                enabled = !processing,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Add this layer to map") }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGdbLayerDialog = false }) { Text("Close") }
            }
        )
    }

    if (showBufferDialog) {
        AlertDialog(
            onDismissRequest = { showBufferDialog = false },
            title = { Text("Vector buffer") },
            text = {
                Column {
                    Text("Distance in the input layer's coordinate units. Use a projected CRS for metre-based buffers.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = bufferDistance, onValueChange = { bufferDistance = it },
                        label = { Text("Buffer distance") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val input = lastVectorPath
                    val distance = bufferDistance.toDoubleOrNull()
                    if (input == null || distance == null || distance <= 0.0) {
                        status = "Enter a positive numeric buffer distance."
                    } else {
                        showBufferDialog = false
                        processing = true
                        status = "Creating vector buffer…"
                        Thread {
                            try {
                                val outputDir = activity.getExternalFilesDir("GeoLite/Processing") ?: activity.filesDir
                                outputDir.mkdirs()
                                val output = File(outputDir, "buffer_${System.currentTimeMillis()}.geojson")
                                GdalEngine.bufferVector(input, output.absolutePath, distance)
                                activity.runOnUiThread {
                                    selectedFile = output.name
                                    status = "Vector buffer created: ${output.absolutePath}. Open this GeoJSON to add it to the map."
                                    processing = false
                                }
                            } catch (e: Exception) {
                                activity.runOnUiThread { status = "Vector buffer failed: ${e.localizedMessage}"; processing = false }
                            }
                        }.start()
                    }
                }) { Text("Run buffer") }
            },
            dismissButton = { TextButton(onClick = { showBufferDialog = false }) { Text("Cancel") } }
        )
    }

    val currentMapView = mapView
    if (currentMapView != null) {
        DisposableEffect(currentMapView, lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> currentMapView.onStart()
                    Lifecycle.Event.ON_RESUME -> currentMapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> currentMapView.onPause()
                    Lifecycle.Event.ON_STOP -> currentMapView.onStop()
                    Lifecycle.Event.ON_DESTROY -> currentMapView.onDestroy()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
}

private fun copyLocalDirectoryToTree(context: android.content.Context, localFolder: File, treeUri: android.net.Uri) {
    require(localFolder.isDirectory && localFolder.name.endsWith(".gdb", true)) { "Source is not a FileGDB folder." }
    val destinationRoot = DocumentFile.fromTreeUri(context, treeUri)
        ?: error("Could not access the selected export folder.")
    require(destinationRoot.isDirectory) { "Choose a destination folder." }
    val exportName = localFolder.nameWithoutExtension + "_edited_${System.currentTimeMillis()}.gdb"
    val targetRoot = destinationRoot.createDirectory(exportName)
        ?: error("Could not create exported FileGDB folder.")
    fun copyFolder(source: File, target: DocumentFile) {
        source.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val childTarget = target.createDirectory(child.name)
                    ?: error("Could not create FileGDB subfolder ${child.name}.")
                copyFolder(child, childTarget)
            } else if (child.isFile) {
                val targetFile = target.createFile("application/octet-stream", child.name)
                    ?: error("Could not create FileGDB component ${child.name}.")
                child.inputStream().use { input ->
                    context.contentResolver.openOutputStream(targetFile.uri, "w")?.use { output ->
                        input.copyTo(output)
                    } ?: error("Could not write FileGDB component ${child.name}.")
                }
            }
        }
    }
    copyFolder(localFolder, targetRoot)
}

private fun createMapMarkerIcon(shape: String, hexColor: String): Bitmap {
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor(hexColor)
        style = Paint.Style.FILL
    }
    when (shape.lowercase()) {
        "square" -> canvas.drawRoundRect(8f, 8f, 40f, 40f, 5f, 5f, paint)
        "star" -> {
            val path = Path()
            for (i in 0 until 10) {
                val angle = Math.PI * i / 5.0 - Math.PI / 2.0
                val radius = if (i % 2 == 0) 20.0 else 9.0
                val x = (24 + radius * kotlin.math.cos(angle)).toFloat()
                val y = (24 + radius * kotlin.math.sin(angle)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, paint)
        }
        "pin" -> {
            val path = Path().apply {
                moveTo(24f, 45f); cubicTo(20f, 36f, 7f, 25f, 7f, 18f)
                cubicTo(7f, 8f, 15f, 3f, 24f, 3f); cubicTo(33f, 3f, 41f, 8f, 41f, 18f)
                cubicTo(41f, 25f, 28f, 36f, 24f, 45f); close()
            }
            canvas.drawPath(path, paint)
            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(24f, 18f, 6f, paint)
        }
        else -> canvas.drawCircle(24f, 24f, 17f, paint)
    }
    return bitmap
}

private fun copyDirectoryTree(context: android.content.Context, treeUri: android.net.Uri): File {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Could not access selected folder.")
    require(root.isDirectory) { "Select a folder containing Shapefile components." }
    val workspace = context.getExternalFilesDir("GeoLite/Workspace") ?: context.filesDir
    workspace.mkdirs()
    val destination = File(workspace, "geolite_shp_${System.currentTimeMillis()}")
    require(destination.mkdirs()) { "Could not create local Shapefile workspace." }
    fun copy(source: DocumentFile, target: File) {
        source.listFiles().forEach { child ->
            val name = child.name ?: error("A selected item has no name.")
            require(name != "." && name != ".." && !name.contains("/") && !name.contains('\\')) {
                "Invalid file or folder name: $name"
            }
            val out = File(target, name)
            if (child.isDirectory) {
                require(out.mkdirs() || out.isDirectory) { "Could not create folder $name" }
                copy(child, out)
            } else if (child.isFile) {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Could not read $name")
            }
        }
    }
    copy(root, destination)
    return destination
}

private fun copyDocumentTree(context: android.content.Context, treeUri: android.net.Uri): File {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Could not access selected folder.")
    require(root.isDirectory) { "Select a FileGDB folder." }
    val safeName = root.name ?: "dataset.gdb"
    require(safeName.endsWith(".gdb", true)) { "Selected folder name must end with .gdb." }
    val workspace = context.getExternalFilesDir("GeoLite/Workspace") ?: context.filesDir
    workspace.mkdirs()
    val destination = File(workspace, "geolite_gdb_${System.currentTimeMillis()}_${safeName}")
    require(destination.mkdirs()) { "Could not create a local copy of the FileGDB." }

    fun copyFolder(source: DocumentFile, target: File) {
        source.listFiles().forEach { child ->
            val childName = child.name ?: error("A FileGDB item has no filename.")
            require(childName != "." && childName != ".." && !childName.contains("/") && !childName.contains('\\')) {
                "Invalid FileGDB component name: $childName"
            }
            val output = File(target, childName)
            if (child.isDirectory) {
                require(output.mkdirs() || output.isDirectory) { "Could not create FileGDB subfolder: $childName" }
                copyFolder(child, output)
            } else if (child.isFile) {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    output.outputStream().use { stream -> input.copyTo(stream) }
                } ?: error("Could not read FileGDB component: $childName")
            }
        }
    }

    copyFolder(root, destination)
    return destination
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return it.getString(index) ?: "Selected dataset"
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "Selected dataset"
}

private fun materializeDocument(context: android.content.Context, uri: android.net.Uri, name: String): File {
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val workspace = context.getExternalFilesDir("GeoLite/Workspace/Imports") ?: context.filesDir
    workspace.mkdirs()
    val target = File(workspace, "geolite_${System.currentTimeMillis()}_$safeName")
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: error("Could not read selected file.")
    return target
}

private fun extractShapefile(zipFile: File): File {
    val outputDir = File(zipFile.parentFile, "unzipped_${zipFile.nameWithoutExtension}")
    outputDir.mkdirs()
    ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val destination = File(outputDir, entry.name).canonicalFile
            require(destination.path.startsWith(outputDir.canonicalPath + File.separator) || destination == outputDir) {
                "Invalid ZIP entry path."
            }
            if (entry.isDirectory) destination.mkdirs() else {
                destination.parentFile?.mkdirs()
                destination.outputStream().use { output -> zip.copyTo(output) }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return outputDir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("shp", true) }
        ?: error("ZIP archive does not contain a Shapefile (.shp).")
}

private fun android.content.Context.findActivity(): ComponentActivity {
    var current: android.content.Context = this
    while (current is android.content.ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("Activity context is unavailable.")
}
