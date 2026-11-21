package com.akib.geolite

import android.Manifest
import android.os.Bundle
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.annotations.MarkerOptions

private const val DEFAULT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private data class Pin(val title: String, val lat: Double, val lon: Double)

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
    val lifecycleOwner = LocalLifecycleOwner.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var selectedPanel by remember { mutableStateOf("Catalog") }
    var styleName by remember { mutableStateOf("OpenFreeMap · Liberty") }
    var styleMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready · Long-press the map to add a placemark") }
    var pins by remember { mutableStateOf(listOf<Pin>()) }
    var selectedFile by remember { mutableStateOf("No dataset loaded") }

    fun addPin(pin: Pin) {
        pins = pins + pin
        map?.addMarker(MarkerOptions().position(LatLng(pin.lat, pin.lon)).title(pin.title)
            .snippet("%.6f, %.6f".format(pin.lat, pin.lon)))
        status = "Placemark added: ${pin.title}"
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            selectedFile = uri.lastPathSegment ?: "Selected dataset"
            status = "Selected file · ${uri.lastPathSegment ?: uri}"
            Toast.makeText(context, "File selected. Native GIS readers are being integrated.", Toast.LENGTH_LONG).show()
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
                    listOf("Catalog", "Layers").forEach { tab ->
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
                } else {
                    Text("MAP LAYERS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("☑  $styleName", modifier = Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                    Text("PLACEMARKS (${pins.size})", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium)
                    LazyColumn {
                        items(pins) { pin ->
                            Text("●  ${pin.title}", style = MaterialTheme.typography.bodySmall)
                            Text("   %.5f, %.5f".format(pin.lat, pin.lon), style = MaterialTheme.typography.labelSmall)
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
                                        addPin(Pin("Placemark ${pins.size + 1}", point.latitude, point.longitude))
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
