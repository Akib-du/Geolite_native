package com.akib.geolite

import android.Manifest
import android.os.Bundle
import android.graphics.PointF
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
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

private const val DEFAULT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
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
    var status by remember { mutableStateOf("Ready · Long-press the map to add a placemark") }
    var pins by remember { mutableStateOf(listOf<Pin>()) }
    var selectedFile by remember { mutableStateOf("No dataset loaded") }
    var vectorLayers by remember { mutableStateOf(listOf<String>()) }
    var vectorOverlays by remember { mutableStateOf(listOf<VectorOverlay>()) }
    var rasterOverlays by remember { mutableStateOf(listOf<RasterOverlay>()) }
    var mapTool by remember { mutableStateOf("Pan") }
    var showStyleDialog by remember { mutableStateOf(false) }
    var showManualPinDialog by remember { mutableStateOf(false) }
    var showCreateLayerDialog by remember { mutableStateOf(false) }
    var showFieldDialog by remember { mutableStateOf(false) }
    var showAttributeEditDialog by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var editFieldName by remember { mutableStateOf("") }
    var editFieldValue by remember { mutableStateOf("") }
    var manualLat by remember { mutableStateOf("") }
    var manualLon by remember { mutableStateOf("") }
    var newLayerName by remember { mutableStateOf("new_points") }
    var selectedFeatureFid by remember { mutableStateOf<Long?>(null) }
    var selectedFeatureProperties by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var vectorColor by remember { mutableStateOf("#2878D0") }
    var vectorWidth by remember { mutableFloatStateOf(2.5f) }
    var pointSize by remember { mutableFloatStateOf(6f) }
    var fillOpacityValue by remember { mutableFloatStateOf(0.30f) }
    var scaleLabel by remember { mutableStateOf("—") }
    var showScale by remember { mutableStateOf(true) }
    var rasterLayers by remember { mutableStateOf(listOf<String>()) }
    var lastVectorPath by remember { mutableStateOf<String?>(null) }
    var lastRasterPath by remember { mutableStateOf<String?>(null) }
    var showBufferDialog by remember { mutableStateOf(false) }
    var bufferDistance by remember { mutableStateOf("100") }
    var processing by remember { mutableStateOf(false) }
    var pendingGdbPath by remember { mutableStateOf<String?>(null) }
    var pendingGdbLayers by remember { mutableStateOf(emptyList<GdalEngine.VectorLayerInfo>()) }
    var showGdbLayerDialog by remember { mutableStateOf(false) }
    var attributeRows by remember { mutableStateOf(listOf<Map<String, String>>()) }
    var selectedAttributeIndex by remember { mutableIntStateOf(0) }
    val currentPinCount = rememberUpdatedState(pins.size)

    fun addVectorOverlayToStyle(style: Style, overlay: VectorOverlay) {
        style.addSource(GeoJsonSource(overlay.id, overlay.geoJson))
        style.addLayer(FillLayer("${overlay.id}_fill", overlay.id).withProperties(
            fillColor(vectorColor), fillOpacity(fillOpacityValue)
        ))
        style.addLayer(LineLayer("${overlay.id}_line", overlay.id).withProperties(
            lineColor(vectorColor), lineWidth(vectorWidth)
        ))
        style.addLayer(CircleLayer("${overlay.id}_point", overlay.id).withProperties(
            circleColor(vectorColor), circleRadius(pointSize)
        ))
    }

    fun addRasterOverlayToStyle(style: Style, overlay: RasterOverlay) {
        val quad = LatLngQuad(
            LatLng(overlay.north, overlay.west), LatLng(overlay.north, overlay.east),
            LatLng(overlay.south, overlay.east), LatLng(overlay.south, overlay.west)
        )
        style.addSource(ImageSource(overlay.id, quad, File(overlay.imagePath).toURI()))
        style.addLayer(RasterLayer("${overlay.id}_layer", overlay.id).withProperties(rasterOpacity(0.88f)))
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
                addVectorOverlayToStyle(style, overlay)
            }
            status = "Layer symbology updated"
        }
    }

    fun addPin(pin: Pin) {
        pins = pins + pin
        map?.addMarker(MarkerOptions().position(LatLng(pin.lat, pin.lon)).title(pin.title)
            .snippet("%.6f, %.6f".format(pin.lat, pin.lon)))
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
        status = "Loading ${layerInfo.name} from FileGDB…"
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
                    TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("＋ Open") }
                    TextButton(onClick = { fileGdbPicker.launch(null) }) { Text("Open .gdb") }
                    Box {
                        TextButton(onClick = { styleMenu = true }) { Text("Basemap ▾") }
                        DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                            DropdownMenuItem(text = { Text("OpenFreeMap · Liberty") }, onClick = {
                                styleName = "OpenFreeMap · Liberty"; styleMenu = false
                                map?.setStyle(DEFAULT_STYLE) { status = "Basemap changed to Liberty" }
                            })
                            DropdownMenuItem(text = { Text("OpenFreeMap · Positron") }, onClick = {
                                styleName = "OpenFreeMap · Positron"; styleMenu = false
                                map?.setStyle("https://tiles.openfreemap.org/styles/positron") { status = "Basemap changed to Positron" }
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
                    Text("Formats planned:", style = MaterialTheme.typography.labelSmall)
                    Text("Shapefile · GeoPackage\nFileGDB · GeoTIFF", style = MaterialTheme.typography.bodySmall)
                } else if (selectedPanel == "Layers") {
                    Text("MAP LAYERS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("☑  $styleName", modifier = Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                    Text("VECTOR LAYERS (${vectorLayers.size})", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium)
                    if (pendingGdbPath != null && pendingGdbLayers.isNotEmpty()) {
                        TextButton(onClick = { showGdbLayerDialog = true }, enabled = !processing) {
                            Text("＋ Add another FileGDB feature class")
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
                                TextButton(onClick = { selectedAttributeIndex = index },
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
                            Text(field, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
                            Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 5.dp))
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
                Text("Raster & vector tools\nComing in native engine phase",
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
                                    status = "Map ready · Long-press to add a placemark"
                                }
                            }
                        }
                    })
                } else {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { view })
                }
            }
        }
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

private fun copyDocumentTree(context: android.content.Context, treeUri: android.net.Uri): File {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Could not access selected folder.")
    require(root.isDirectory) { "Select a FileGDB folder." }
    val safeName = root.name ?: "dataset.gdb"
    require(safeName.endsWith(".gdb", true)) { "Selected folder name must end with .gdb." }
    val destination = File(context.cacheDir, "geolite_gdb_${System.currentTimeMillis()}_${safeName}")
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
    val target = File(context.cacheDir, "geolite_${System.currentTimeMillis()}_$safeName")
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
