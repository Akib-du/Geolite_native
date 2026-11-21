package com.akib.geolite

import org.gdal.gdal.gdal
import org.gdal.gdalconst.gdalconstConstants
import org.gdal.ogr.ogr
import org.gdal.osr.SpatialReference
import org.gdal.osr.CoordinateTransformation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Android GDAL/OGR bridge.
 * GDAL 3.7.0 Android build: arm64-v8a; drivers include
 * GeoTIFF, Erdas IMG, Shapefile, KML and GeoJSON.
 */
object GdalEngine {
    @Volatile private var ready = false

    @Synchronized
    fun initialize(): String {
        if (!ready) {
            gdal.AllRegister()
            ogr.RegisterAll()
            ready = true
        }
        return gdal.VersionInfo("RELEASE_NAME")
    }

    data class VectorResult(val geoJson: String, val featureCount: Long, val layerName: String)
    data class VectorLayerInfo(val index: Int, val name: String, val featureCount: Long)
    data class RasterInfo(
        val width: Int, val height: Int, val bands: Int,
        val projection: String, val dataType: String, val geoTransform: DoubleArray
    )

    fun hasOpenFileGdbDriver(): Boolean {
        initialize()
        return ogr.GetDriverByName("OpenFileGDB") != null
    }

    fun listVectorLayers(path: String): List<VectorLayerInfo> {
        initialize()
        val dataSource = ogr.Open(path, 0) ?: error("OGR could not open this dataset. OpenFileGDB driver available: ${hasOpenFileGdbDriver()}")
        try {
            return (0 until dataSource.GetLayerCount()).map { index ->
                val layer = dataSource.GetLayer(index) ?: error("Could not read layer $index.")
                VectorLayerInfo(index, layer.GetName(), layer.GetFeatureCount())
            }
        } finally { dataSource.delete() }
    }

    fun openVector(path: String): VectorResult = openVector(path, 0)

    fun openVector(path: String, layerIndex: Int): VectorResult {
        initialize()
        val dataSource = ogr.Open(path, 0) ?: error("OGR could not open this vector dataset. For Shapefile, choose a ZIP containing .shp, .shx and .dbf.")
        try {
            require(dataSource.GetLayerCount() > 0) { "No vector layers were found." }
            require(layerIndex in 0 until dataSource.GetLayerCount()) { "Vector layer index is out of range." }
            val layer = dataSource.GetLayer(layerIndex) ?: error("Could not read vector layer $layerIndex.")
            val definition = layer.GetLayerDefn()
            val target = SpatialReference()
            target.ImportFromEPSG(4326)
            val source = layer.GetSpatialRef()
            val collection = JSONObject()
            collection.put("type", "FeatureCollection")
            val features = JSONArray()
            layer.ResetReading()
            var feature = layer.GetNextFeature()
            var count = 0L
            while (feature != null) {
                val properties = JSONObject().put("__fid", feature.GetFID())
                for (i in 0 until feature.GetFieldCount()) {
                    val fieldName = definition.GetFieldDefn(i).GetNameRef()
                    properties.put(fieldName, feature.GetFieldAsString(i))
                }
                val geometry = feature.GetGeometryRef()
                val geometryJson = if (geometry == null) JSONObject.NULL else {
                    val copy = geometry.Clone()
                    if (source != null) {
                        val transformResult = copy.TransformTo(target)
                        require(transformResult == 0) { "Could not transform layer coordinates to WGS 84." }
                    }
                    val json = JSONObject(copy.ExportToJson())
                    copy.delete()
                    json
                }
                features.put(JSONObject().put("type", "Feature")
                    .put("geometry", geometryJson).put("properties", properties))
                feature.delete()
                count++
                feature = layer.GetNextFeature()
            }
            collection.put("features", features)
            target.delete()
            return VectorResult(collection.toString(), count, layer.GetName())
        } finally {
            dataSource.delete()
        }
    }

    data class RasterImageInfo(val imagePath: String, val west: Double, val north: Double, val east: Double, val south: Double)

    fun hasRasterDriver(name: String): Boolean {
        initialize()
        return gdal.GetDriverByName(name) != null
    }

    fun hasVectorDriver(name: String): Boolean {
        initialize()
        return ogr.GetDriverByName(name) != null
    }

    /** Reproject a raster to WGS84 and make a bounded PNG for MapLibre's georeferenced ImageSource. */
    fun createMapImage(inputPath: String, outputPath: String): RasterImageInfo {
        initialize()
        val source = gdal.Open(inputPath, gdalconstConstants.GA_ReadOnly)
            ?: error("GDAL could not open the raster.")
        val warpedPath = outputPath.removeSuffix(".png") + "_wgs84.tif"
        try {
            val warpOptions = org.gdal.gdal.WarpOptions(java.util.Vector<String>().apply {
                addAll(listOf("-t_srs", "EPSG:4326", "-of", "GTiff", "-ts", "2048", "0"))
            })
            val warped = gdal.Warp(warpedPath, arrayOf(source), warpOptions)
                ?: error("Could not reproject the raster preview to WGS84.")
            try {
                val gt = warped.GetGeoTransform()
                val west = gt[0]
                val north = gt[3]
                val east = west + warped.GetRasterXSize() * gt[1]
                val south = north + warped.GetRasterYSize() * gt[5]
                require(listOf(west, north, east, south).all { it.isFinite() } && east > west && north > south) {
                    "Raster has invalid or unsupported geographic bounds."
                }

                val bandCount = minOf(warped.GetRasterCount(), 4)
                require(bandCount > 0) { "Raster has no displayable bands." }
                val translateArgs = java.util.Vector<String>().apply {
                    addAll(listOf("-of", "PNG", "-ot", "Byte"))
                    for (bandIndex in 1..bandCount) {
                        val band = warped.GetRasterBand(bandIndex)
                            ?: error("Could not read raster band $bandIndex.")
                        val minValues = DoubleArray(1)
                        val maxValues = DoubleArray(1)
                        val meanValues = DoubleArray(1)
                        val stdDevValues = DoubleArray(1)
                        val statsResult = band.GetStatistics(0, 1, minValues, maxValues, meanValues, stdDevValues)
                        require(statsResult == 0) { "Could not calculate display statistics for raster band $bandIndex." }
                        var min = minValues[0]
                        var max = maxValues[0]
                        if (!min.isFinite() || !max.isFinite() || max <= min) {
                            min = 0.0
                            max = 255.0
                        }
                        addAll(listOf("-b", bandIndex.toString(), "-scale_$bandIndex",
                            min.toString(), max.toString(), "0", "255"))
                    }
                }
                val translateOptions = org.gdal.gdal.TranslateOptions(translateArgs)
                val png = gdal.Translate(outputPath, warped, translateOptions)
                    ?: error("Could not convert the raster to a PNG map preview.")
                try {
                    return RasterImageInfo(outputPath, west, north, east, south)
                } finally {
                    png.delete()
                    translateOptions.delete()
                }
            } finally {
                warped.delete()
                warpOptions.delete()
                File(warpedPath).delete()
            }
        } finally {
            source.delete()
        }
    }

    /** Create an editable point Shapefile feature class in WGS84. */
    fun createShapefile(path: String, layerName: String, geometryType: String) {
        initialize()
        val geometry = when (geometryType.lowercase()) {
            "point" -> ogr.wkbPoint
            "line" -> ogr.wkbLineString
            "polygon" -> ogr.wkbPolygon
            else -> error("Choose Point, Line or Polygon geometry.")
        }
        val driver = ogr.GetDriverByName("ESRI Shapefile") ?: error("Shapefile driver unavailable.")
        val dataSource = driver.CreateDataSource(path) ?: error("Could not create Shapefile.")
        try {
            val srs = SpatialReference()
            srs.ImportFromEPSG(4326)
            try {
                dataSource.CreateLayer(layerName, srs, geometry)
                    ?: error("Could not create $geometryType feature class.")
            } finally { srs.delete() }
        } finally { dataSource.delete() }
    }

    fun createPointShapefile(path: String, layerName: String) = createShapefile(path, layerName, "Point")

    /** Create a feature class in a new or existing FileGDB using OpenFileGDB. Returns the layer index. */
    fun createFileGdbFeatureClass(gdbPath: String, layerName: String, geometryType: String): Int {
        initialize()
        require(gdbPath.endsWith(".gdb", true)) { "FileGDB path must end with .gdb." }
        val geometry = when (geometryType.lowercase()) {
            "point" -> ogr.wkbPoint
            "line" -> ogr.wkbLineString
            "polygon" -> ogr.wkbPolygon
            else -> error("Choose Point, Line or Polygon geometry.")
        }
        val driver = ogr.GetDriverByName("OpenFileGDB")
            ?: error("OpenFileGDB driver is not available in this GDAL build.")
        val dataSource = if (File(gdbPath).exists()) {
            ogr.Open(gdbPath, 1) ?: error("Could not open the FileGDB copy for update.")
        } else {
            driver.CreateDataSource(gdbPath) ?: error("Could not create the FileGDB.")
        }
        try {
            val srs = SpatialReference()
            srs.ImportFromEPSG(4326)
            try {
                dataSource.CreateLayer(layerName, srs, geometry)
                    ?: error("Could not create FileGDB feature class.")
            } finally { srs.delete() }
            return dataSource.GetLayerCount() - 1
        } finally { dataSource.delete() }
    }

    private fun requireEditableVectorPath(path: String) {
        require(path.endsWith(".shp", true) || path.endsWith(".gdb", true)) {
            "Editing is supported for Shapefile and FileGDB datasets. GPX layers are read-only."
        }
    }

    fun addField(path: String, layerIndex: Int, fieldName: String, fieldType: String = "String") {
        requireEditableVectorPath(path)
        val maxFieldLength = if (path.endsWith(".shp", true)) 10 else 64
        require(fieldName.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,${maxFieldLength - 1}}"))) {
            "Field name must start with a letter and contain only letters, numbers or underscores (max $maxFieldLength characters)."
        }
        val ds = ogr.Open(path, 1) ?: error("Could not open this dataset for editing. It may be read-only.")
        try {
            val layer = ds.GetLayer(layerIndex) ?: error("Layer not found.")
            val ogrType = when (fieldType.lowercase()) {
                "integer" -> ogr.OFTInteger
                "real" -> ogr.OFTReal
                "date" -> ogr.OFTDate
                else -> ogr.OFTString
            }
            val field = org.gdal.ogr.FieldDefn(fieldName, ogrType)
            try {
                require(layer.CreateField(field) == 0) { "Could not add field. It may already exist or exceed driver limits." }
            } finally { field.delete() }
        } finally { ds.delete() }
    }

    fun updateAttribute(path: String, layerIndex: Int, featureId: Long, fieldName: String, value: String) {
        requireEditableVectorPath(path)
        val ds = ogr.Open(path, 1) ?: error("Could not open this dataset for editing.")
        try {
            val layer = ds.GetLayer(layerIndex) ?: error("Layer not found.")
            val feature = layer.GetFeature(featureId) ?: error("Feature not found.")
            try {
                feature.SetField(fieldName, value)
                require(layer.SetFeature(feature) == 0) { "Could not save the attribute change." }
            } finally { feature.delete() }
        } finally { ds.delete() }
    }

    fun deleteFeature(path: String, layerIndex: Int, featureId: Long) {
        requireEditableVectorPath(path)
        val ds = ogr.Open(path, 1) ?: error("Could not open this dataset for editing.")
        try {
            val layer = ds.GetLayer(layerIndex) ?: error("Layer not found.")
            require(layer.DeleteFeature(featureId) == 0) { "Could not delete feature." }
        } finally { ds.delete() }
    }

    fun movePointFeature(path: String, layerIndex: Int, featureId: Long, longitude: Double, latitude: Double) {
        requireEditableVectorPath(path)
        require(longitude in -180.0..180.0 && latitude in -90.0..90.0) { "Coordinates are outside valid WGS84 ranges." }
        val ds = ogr.Open(path, 1) ?: error("Could not open this dataset for editing.")
        try {
            val layer = ds.GetLayer(layerIndex) ?: error("Layer not found.")
            val feature = layer.GetFeature(featureId) ?: error("Feature not found.")
            val geometry = org.gdal.ogr.Geometry(ogr.wkbPoint)
            try {
                require(feature.GetGeometryRef()?.GetGeometryType() == ogr.wkbPoint) {
                    "Move-point currently works with Point features only."
                }
                geometry.AddPoint_2D(longitude, latitude)
                feature.SetGeometry(geometry)
                require(layer.SetFeature(feature) == 0) { "Could not save the moved point." }
            } finally {
                geometry.delete()
                feature.delete()
            }
        } finally { ds.delete() }
    }

    fun createPointFeature(path: String, layerIndex: Int, longitude: Double, latitude: Double) {
        requireEditableVectorPath(path)
        require(longitude in -180.0..180.0 && latitude in -90.0..90.0) { "Coordinates are outside valid WGS84 ranges." }
        val ds = ogr.Open(path, 1) ?: error("Could not open this dataset for editing.")
        try {
            val layer = ds.GetLayer(layerIndex) ?: error("Layer not found.")
            val definition = layer.GetLayerDefn()
            val feature = org.gdal.ogr.Feature(definition)
            val geometry = org.gdal.ogr.Geometry(ogr.wkbPoint)
            try {
                geometry.AddPoint_2D(longitude, latitude)
                feature.SetGeometry(geometry)
                require(layer.CreateFeature(feature) == 0) { "Could not create point feature." }
            } finally {
                geometry.delete()
                feature.delete()
            }
        } finally { ds.delete() }
    }

    /** Digitize a line or polygon from WGS84 map-click vertices. */
    fun createGeometryFeature(path: String, layerIndex: Int, geometryType: String, coordinates: List<Pair<Double, Double>>) {
        requireEditableVectorPath(path)
        require(coordinates.size >= if (geometryType.equals("Polygon", true)) 3 else 2) {
            if (geometryType.equals("Polygon", true)) "A polygon needs at least 3 vertices." else "A line needs at least 2 vertices."
        }
        coordinates.forEach { (lon, lat) ->
            require(lon in -180.0..180.0 && lat in -90.0..90.0) { "Coordinate outside WGS84 range." }
        }
        val ds = ogr.Open(path, 1) ?: error("Could not open dataset for editing.")
        try {
            val layer = ds.GetLayer(layerIndex) ?: error("Layer not found.")
            val expectedType = if (geometryType.equals("Line", true)) ogr.wkbLineString else ogr.wkbPolygon
            require(ogr.GT_Flatten(layer.GetGeomType()) == expectedType) {
                "The active layer is not a $geometryType layer. Create or select a matching feature class first."
            }
            val feature = org.gdal.ogr.Feature(layer.GetLayerDefn())
            val geometry = when (geometryType.lowercase()) {
                "line" -> org.gdal.ogr.Geometry(ogr.wkbLineString).also { line ->
                    coordinates.forEach { (lon, lat) -> line.AddPoint_2D(lon, lat) }
                }
                "polygon" -> {
                    val ring = org.gdal.ogr.Geometry(ogr.wkbLinearRing)
                    coordinates.forEach { (lon, lat) -> ring.AddPoint_2D(lon, lat) }
                    val first = coordinates.first()
                    val last = coordinates.last()
                    if (first != last) ring.AddPoint_2D(first.first, first.second)
                    org.gdal.ogr.Geometry(ogr.wkbPolygon).also { polygon ->
                        try { require(polygon.AddGeometry(ring) == 0) { "Could not build polygon ring." } }
                        finally { ring.delete() }
                    }
                }
                else -> error("Only Line and Polygon are supported by this digitizing tool.")
            }
            try {
                feature.SetGeometry(geometry)
                require(layer.CreateFeature(feature) == 0) { "Could not save digitized $geometryType." }
            } finally { geometry.delete(); feature.delete() }
        } finally { ds.delete() }
    }

    /** Move the nearest vertex of a selected line or polygon to the tapped WGS84 coordinate. */
    fun moveNearestVertex(path: String, layerIndex: Int, featureId: Long, longitude: Double, latitude: Double): Int {
        requireEditableVectorPath(path)
        require(longitude in -180.0..180.0 && latitude in -90.0..90.0) { "Coordinates outside WGS84 range." }
        val ds = ogr.Open(path, 1) ?: error("Could not open dataset for editing.")
        try {
            val layer = ds.GetLayer(layerIndex) ?: error("Layer not found.")
            val feature = layer.GetFeature(featureId) ?: error("Feature not found.")
            try {
                val sourceGeometry = feature.GetGeometryRef() ?: error("Selected feature has no geometry.")
                val geometry = sourceGeometry.Clone()
                try {
                    val type = org.gdal.ogr.ogr.GT_Flatten(geometry.GetGeometryType())
                    val editable = when (type) {
                        ogr.wkbLineString -> geometry
                        ogr.wkbPolygon -> geometry.GetGeometryRef(0) ?: error("Polygon has no exterior ring.")
                        else -> error("Vertex editing supports Line and Polygon features only.")
                    }
                    val count = editable.GetPointCount()
                    require(count > 0) { "Selected geometry contains no vertices." }
                    var nearest = 0
                    var best = Double.POSITIVE_INFINITY
                    for (i in 0 until count) {
                        val vertex = editable.GetPoint(i)
                        val dx = (vertex[0] - longitude) * kotlin.math.cos(Math.toRadians(latitude))
                        val dy = vertex[1] - latitude
                        val distance = dx * dx + dy * dy
                        if (distance < best) { best = distance; nearest = i }
                    }
                    editable.SetPoint_2D(nearest, longitude, latitude)
                    if (type == ogr.wkbPolygon && count > 1) {
                        val first = editable.GetPoint(0)
                        val last = editable.GetPoint(count - 1)
                        if (kotlin.math.abs(first[0] - last[0]) < 1e-12 &&
                            kotlin.math.abs(first[1] - last[1]) < 1e-12) {
                            if (nearest == 0) editable.SetPoint_2D(count - 1, longitude, latitude)
                            if (nearest == count - 1) editable.SetPoint_2D(0, longitude, latitude)
                        }
                    }
                    feature.SetGeometry(geometry)
                    require(layer.SetFeature(feature) == 0) {
                        "Could not save vertex edit."
                    }
                    return nearest
                } finally { geometry.delete() }
            } finally { feature.delete() }
        } finally { ds.delete() }
    }

    /** Insert a vertex at the tapped coordinate on the nearest segment of a line or polygon. */
    fun insertNearestVertex(path: String, layerIndex: Int, featureId: Long, longitude: Double, latitude: Double): Int {
        requireEditableVectorPath(path)
        require(longitude in -180.0..180.0 && latitude in -90.0..90.0) { "Coordinates outside WGS84 range." }
        val ds = ogr.Open(path, 1) ?: error("Could not open dataset for editing.")
        try {
            val layer = ds.GetLayer(layerIndex) ?: error("Layer not found.")
            val feature = layer.GetFeature(featureId) ?: error("Feature not found.")
            try {
                val geometry = feature.GetGeometryRef()?.Clone() ?: error("Selected feature has no geometry.")
                try {
                    val type = ogr.GT_Flatten(geometry.GetGeometryType())
                    val editable = when (type) {
                        ogr.wkbLineString -> geometry
                        ogr.wkbPolygon -> geometry.GetGeometryRef(0) ?: error("Polygon has no exterior ring.")
                        else -> error("Vertex insertion supports Line and Polygon features only.")
                    }
                    val count = editable.GetPointCount()
                    require(count >= 2) { "Geometry has too few vertices." }
                    var insertAfter = 0
                    var best = Double.POSITIVE_INFINITY
                    for (i in 0 until count - 1) {
                        val a = editable.GetPoint(i)
                        val b = editable.GetPoint(i + 1)
                        val cosLat = kotlin.math.cos(Math.toRadians(latitude))
                        val ax = a[0] * cosLat; val ay = a[1]
                        val bx = b[0] * cosLat; val by = b[1]
                        val px = longitude * cosLat; val py = latitude
                        val dx = bx - ax; val dy = by - ay
                        val length2 = dx * dx + dy * dy
                        val t = if (length2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / length2).coerceIn(0.0, 1.0)
                        val ex = px - (ax + t * dx); val ey = py - (ay + t * dy)
                        val distance = ex * ex + ey * ey
                        if (distance < best) { best = distance; insertAfter = i }
                    }
                    val insertAt = insertAfter + 1
                    val replacement = if (type == ogr.wkbLineString) {
                        org.gdal.ogr.Geometry(ogr.wkbLineString).also { line ->
                            for (i in 0 until count) {
                                val p = editable.GetPoint(i)
                                line.AddPoint_2D(p[0], p[1])
                                if (i == insertAfter) line.AddPoint_2D(longitude, latitude)
                            }
                        }
                    } else {
                        val ring = org.gdal.ogr.Geometry(ogr.wkbLinearRing)
                        for (i in 0 until count - 1) {
                            val p = editable.GetPoint(i)
                            ring.AddPoint_2D(p[0], p[1])
                            if (i == insertAfter) ring.AddPoint_2D(longitude, latitude)
                        }
                        val first = ring.GetPoint(0)
                        ring.AddPoint_2D(first[0], first[1])
                        org.gdal.ogr.Geometry(ogr.wkbPolygon).also { polygon ->
                            try { require(polygon.AddGeometry(ring) == 0) { "Could not rebuild polygon ring." } }
                            finally { ring.delete() }
                        }
                    }
                    try {
                        feature.SetGeometry(replacement)
                        require(layer.SetFeature(feature) == 0) { "Could not save inserted vertex." }
                    } finally { replacement.delete() }
                    return insertAt
                } finally { geometry.delete() }
            } finally { feature.delete() }
        } finally { ds.delete() }
    }

    /** Delete the nearest vertex while preserving minimum line/polygon vertex counts and polygon closure. */
    fun deleteNearestVertex(path: String, layerIndex: Int, featureId: Long, longitude: Double, latitude: Double): Int {
        requireEditableVectorPath(path)
        require(longitude in -180.0..180.0 && latitude in -90.0..90.0) { "Coordinates outside WGS84 range." }
        val ds = ogr.Open(path, 1) ?: error("Could not open dataset for editing.")
        try {
            val layer = ds.GetLayer(layerIndex) ?: error("Layer not found.")
            val feature = layer.GetFeature(featureId) ?: error("Feature not found.")
            try {
                val geometry = feature.GetGeometryRef()?.Clone() ?: error("Selected feature has no geometry.")
                try {
                    val type = ogr.GT_Flatten(geometry.GetGeometryType())
                    val editable = when (type) {
                        ogr.wkbLineString -> geometry
                        ogr.wkbPolygon -> geometry.GetGeometryRef(0) ?: error("Polygon has no exterior ring.")
                        else -> error("Vertex deletion supports Line and Polygon features only.")
                    }
                    val count = editable.GetPointCount()
                    val polygon = type == ogr.wkbPolygon
                    val minimum = if (polygon) 4 else 2
                    require(count > minimum) { if (polygon) "A polygon must retain at least 3 distinct vertices." else "A line must retain at least 2 vertices." }
                    var nearest = 0
                    var best = Double.POSITIVE_INFINITY
                    for (i in 0 until count) {
                        if (polygon && i == count - 1) continue
                        val p = editable.GetPoint(i)
                        val dx = (p[0] - longitude) * kotlin.math.cos(Math.toRadians(latitude))
                        val dy = p[1] - latitude
                        val distance = dx * dx + dy * dy
                        if (distance < best) { best = distance; nearest = i }
                    }
                    val replacement = if (polygon) {
                        val ring = org.gdal.ogr.Geometry(ogr.wkbLinearRing)
                        for (i in 0 until count - 1) {
                            if (i == nearest) continue
                            val p = editable.GetPoint(i)
                            ring.AddPoint_2D(p[0], p[1])
                        }
                        val first = ring.GetPoint(0)
                        ring.AddPoint_2D(first[0], first[1])
                        org.gdal.ogr.Geometry(ogr.wkbPolygon).also { rebuilt ->
                            try { require(rebuilt.AddGeometry(ring) == 0) { "Could not rebuild polygon ring." } }
                            finally { ring.delete() }
                        }
                    } else {
                        org.gdal.ogr.Geometry(ogr.wkbLineString).also { line ->
                            for (i in 0 until count) {
                                if (i == nearest) continue
                                val p = editable.GetPoint(i)
                                line.AddPoint_2D(p[0], p[1])
                            }
                        }
                    }
                    try {
                        feature.SetGeometry(replacement)
                        require(layer.SetFeature(feature) == 0) { "Could not save deleted vertex." }
                    } finally { replacement.delete() }
                    return nearest
                } finally { geometry.delete() }
            } finally { feature.delete() }
        } finally { ds.delete() }
    }

    fun openRaster(path: String): RasterInfo {
        initialize()
        val dataset = gdal.Open(path, gdalconstConstants.GA_ReadOnly)
            ?: error("GDAL could not open this raster. Supported drivers in this build include GeoTIFF and Erdas IMG.")
        try {
            val firstBand = dataset.GetRasterBand(1)
            val type = if (firstBand == null) "Unknown" else gdal.GetDataTypeName(firstBand.GetRasterDataType())
            return RasterInfo(
                dataset.GetRasterXSize(), dataset.GetRasterYSize(), dataset.GetRasterCount(),
                dataset.GetProjectionRef() ?: "", type, dataset.GetGeoTransform()
            )
        } finally {
            dataset.delete()
        }
    }

    /** Reproject one vector layer into a new WGS84/target-CRS Shapefile. */
    fun reprojectVector(inputPath: String, layerIndex: Int, outputPath: String, targetCrs: String): Long {
        initialize()
        val epsg = Regex("^EPSG:(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(targetCrs.trim())
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: error("Enter a target CRS as EPSG:<code>, for example EPSG:32646.")
        val input = ogr.Open(inputPath, 0) ?: error("Could not open input vector dataset.")
        try {
            val layer = input.GetLayer(layerIndex) ?: error("Input vector layer not found.")
            val sourceSrs = layer.GetSpatialRef() ?: error("Input layer has no defined CRS.")
            val targetSrs = SpatialReference()
            require(targetSrs.ImportFromEPSG(epsg) == 0) { "Could not read target EPSG:$epsg." }
            val transform = CoordinateTransformation(sourceSrs, targetSrs)
            val driver = ogr.GetDriverByName("ESRI Shapefile") ?: error("Shapefile output driver unavailable.")
            val output = driver.CreateDataSource(outputPath) ?: error("Could not create output Shapefile.")
            try {
                val layerName = File(outputPath).nameWithoutExtension
                val outLayer = output.CreateLayer(layerName, targetSrs, layer.GetGeomType())
                    ?: error("Could not create output feature class.")
                val definition = layer.GetLayerDefn()
                for (i in 0 until definition.GetFieldCount()) {
                    outLayer.CreateField(definition.GetFieldDefn(i))
                }
                val outDefinition = outLayer.GetLayerDefn()
                layer.ResetReading()
                var feature = layer.GetNextFeature()
                var count = 0L
                while (feature != null) {
                    val geometry = feature.GetGeometryRef()
                    val outputFeature = org.gdal.ogr.Feature(outDefinition)
                    try {
                        if (geometry != null) {
                            val copy = geometry.Clone()
                            try {
                                require(copy.Transform(transform) == 0) { "Coordinate transformation failed for feature ${feature.GetFID()}." }
                                outputFeature.SetGeometry(copy)
                            } finally { copy.delete() }
                        }
                        for (i in 0 until feature.GetFieldCount()) {
                            outputFeature.SetField(i, feature.GetFieldAsString(i))
                        }
                        require(outLayer.CreateFeature(outputFeature) == 0) { "Could not write reprojected feature." }
                        count++
                    } finally {
                        outputFeature.delete()
                        feature.delete()
                    }
                    feature = layer.GetNextFeature()
                }
                return count
            } finally {
                output.delete()
                transform.delete()
                targetSrs.delete()
            }
        } finally { input.delete() }
    }

    /** GDAL Warp-based reprojection/resampling. Output is written as GeoTIFF. */
    fun reprojectRaster(inputPath: String, outputPath: String, targetCrs: String, pixelSize: Double? = null) {
        initialize()
        val options = java.util.Vector<String>().apply { addAll(listOf("-t_srs", targetCrs, "-of", "GTiff")) }
        if (pixelSize != null && pixelSize > 0.0) {
            options.addAll(listOf("-tr", pixelSize.toString(), pixelSize.toString(), "-r", "bilinear"))
        }
        val source = gdal.Open(inputPath, gdalconstConstants.GA_ReadOnly)
            ?: error("Could not open input raster.")
        try {
            val warpOptions = org.gdal.gdal.WarpOptions(options)
            val output = gdal.Warp(outputPath, arrayOf(source), warpOptions)
                ?: error("GDAL Warp failed.")
            output.delete()
            warpOptions.delete()
        } finally {
            source.delete()
        }
    }


    /** Create an OGR geometry buffer and write the result as GeoJSON. Distance uses layer units. */
    fun bufferVector(inputPath: String, outputPath: String, distance: Double) {
        initialize()
        require(distance > 0.0) { "Buffer distance must be greater than zero." }
        val input = ogr.Open(inputPath, 0) ?: error("Could not open input vector.")
        try {
            val layer = input.GetLayer(0) ?: error("Input has no vector layer.")
            val driver = ogr.GetDriverByName("GeoJSON") ?: error("GeoJSON output driver is unavailable.")
            val output = driver.CreateDataSource(outputPath) ?: error("Could not create output GeoJSON.")
            try {
                val outputLayer = output.CreateLayer(layer.GetName() + "_buffer", layer.GetSpatialRef(), layer.GetGeomType())
                    ?: error("Could not create output layer.")
                val definition = layer.GetLayerDefn()
                for (i in 0 until definition.GetFieldCount()) outputLayer.CreateField(definition.GetFieldDefn(i))
                val outputDefinition = outputLayer.GetLayerDefn()
                layer.ResetReading()
                var feature = layer.GetNextFeature()
                while (feature != null) {
                    val geometry = feature.GetGeometryRef()
                    if (geometry != null) {
                        val buffered = geometry.Buffer(distance, 12)
                        val outFeature = org.gdal.ogr.Feature(outputDefinition)
                        outFeature.SetGeometry(buffered)
                        for (i in 0 until feature.GetFieldCount()) outFeature.SetField(i, feature.GetFieldAsString(i))
                        outputLayer.CreateFeature(outFeature)
                        outFeature.delete()
                        buffered.delete()
                    }
                    feature.delete()
                    feature = layer.GetNextFeature()
                }
            } finally {
                output.delete()
            }
        } finally {
            input.delete()
        }
    }


    /** Clip a raster using a polygon vector mask. GDAL handles on-the-fly CRS transformation. */
    fun clipRasterByMask(inputPath: String, maskPath: String, outputPath: String, layerIndex: Int = 0) {
        initialize()
        require(File(maskPath).exists()) { "Mask vector dataset does not exist." }
        val mask = ogr.Open(maskPath, 0) ?: error("Could not open polygon mask dataset.")
        val layerName = try {
            val layer = mask.GetLayer(layerIndex) ?: error("Mask layer not found.")
            val geometryType = ogr.GT_Flatten(layer.GetGeomType())
            require(geometryType == ogr.wkbPolygon || geometryType == ogr.wkbMultiPolygon) {
                "Mask layer must contain Polygon or MultiPolygon geometry."
            }
            layer.GetName()
        } finally { mask.delete() }
        val source = gdal.Open(inputPath, gdalconstConstants.GA_ReadOnly)
            ?: error("Could not open input raster.")
        try {
            val args = java.util.Vector<String>().apply {
                addAll(listOf("-of", "GTiff", "-cutline", maskPath, "-cl", layerName,
                    "-crop_to_cutline", "-dstalpha"))
            }
            val options = org.gdal.gdal.WarpOptions(args)
            try {
                val output = gdal.Warp(outputPath, arrayOf(source), options)
                    ?: error("Mask clipping failed. Check that the polygon overlaps the raster.")
                output.delete()
            } finally { options.delete() }
        } finally { source.delete() }
    }

    /** Pixel-wise raster algebra for aligned single-band rasters. Expressions support A, B, numbers,
     * parentheses and +, -, *, /, ^ operators. */
    fun rasterCalculator(inputAPath: String, inputBPath: String?, expression: String, outputPath: String) {
        initialize()
        val a = gdal.Open(inputAPath, gdalconstConstants.GA_ReadOnly)
            ?: error("Could not open raster A.")
        var b: org.gdal.gdal.Dataset? = null
        var output: org.gdal.gdal.Dataset? = null
        try {
            val width = a.GetRasterXSize()
            val height = a.GetRasterYSize()
            val bandA = a.GetRasterBand(1) ?: error("Raster A has no band 1.")
            val bandB = if (!inputBPath.isNullOrBlank()) {
                b = gdal.Open(inputBPath, gdalconstConstants.GA_ReadOnly)
                    ?: error("Could not open raster B.")
                require(b!!.GetRasterXSize() == width && b!!.GetRasterYSize() == height) {
                    "Raster A and B must have identical dimensions. Reproject/resample them to the same grid first."
                }
                require(a.GetProjectionRef() == b!!.GetProjectionRef() &&
                    a.GetGeoTransform().contentEquals(b!!.GetGeoTransform())) {
                    "Raster A and B must have the same CRS, cell size and grid alignment."
                }
                b!!.GetRasterBand(1) ?: error("Raster B has no band 1.")
            } else null
            val evaluator = RasterExpression(expression, bandB != null)
            val driver = gdal.GetDriverByName("GTiff") ?: error("GeoTIFF output driver unavailable.")
            output = driver.Create(outputPath, width, height, 1, gdalconstConstants.GDT_Float32)
                ?: error("Could not create calculator output.")
            output!!.SetGeoTransform(a.GetGeoTransform())
            output!!.SetProjection(a.GetProjectionRef())
            val outBand = output!!.GetRasterBand(1) ?: error("Could not create output band.")
            outBand.SetNoDataValue(Double.NaN)
            val rowA = DoubleArray(width)
            val rowB = DoubleArray(width)
            val rowOut = DoubleArray(width)
            val noDataA = bandA.GetNoDataValue()
            val noDataB = bandB?.GetNoDataValue()
            for (y in 0 until height) {
                require(bandA.ReadRaster(0, y, width, 1, rowA) == 0) { "Could not read raster A row $y." }
                if (bandB != null) require(bandB.ReadRaster(0, y, width, 1, rowB) == 0) { "Could not read raster B row $y." }
                for (x in 0 until width) {
                    val av = rowA[x]
                    val bv = if (bandB != null) rowB[x] else Double.NaN
                    rowOut[x] = if (!av.isFinite() || (noDataA != null && av == noDataA) ||
                        (bandB != null && (!bv.isFinite() || (noDataB != null && bv == noDataB)))) {
                        Double.NaN
                    } else {
                        evaluator.evaluate(av, bv).let { if (it.isFinite()) it else Double.NaN }
                    }
                }
                require(outBand.WriteRaster(0, y, width, 1, rowOut) == 0) { "Could not write calculator output row $y." }
            }
            output!!.FlushCache()
        } finally {
            output?.delete()
            b?.delete()
            a.delete()
        }
    }

    private class RasterExpression(source: String, allowB: Boolean) {
        private var position = 0
        private val expression = source.replace(" ", "").uppercase()
        private val root: (Double, Double) -> Double

        init {
            require(expression.isNotBlank()) { "Enter a raster expression." }
            root = parseAddSub(allowB)
            require(position == expression.length) { "Unexpected character at position ${position + 1}." }
        }

        fun evaluate(a: Double, b: Double): Double = root(a, b)

        private fun parseAddSub(allowB: Boolean): (Double, Double) -> Double {
            var left = parseMulDiv(allowB)
            while (position < expression.length && (expression[position] == '+' || expression[position] == '-')) {
                val op = expression[position++]
                val right = parseMulDiv(allowB)
                val previous = left
                left = if (op == '+') ({ a, b -> previous(a, b) + right(a, b) })
                    else ({ a, b -> previous(a, b) - right(a, b) })
            }
            return left
        }

        private fun parseMulDiv(allowB: Boolean): (Double, Double) -> Double {
            var left = parsePower(allowB)
            while (position < expression.length && (expression[position] == '*' || expression[position] == '/')) {
                val op = expression[position++]
                val right = parsePower(allowB)
                val previous = left
                left = if (op == '*') ({ a, b -> previous(a, b) * right(a, b) })
                    else ({ a, b -> previous(a, b) / right(a, b) })
            }
            return left
        }

        private fun parsePower(allowB: Boolean): (Double, Double) -> Double {
            val left = parseUnary(allowB)
            if (position < expression.length && expression[position] == '^') {
                position++
                val right = parsePower(allowB)
                return { a, b -> Math.pow(left(a, b), right(a, b)) }
            }
            return left
        }

        private fun parseUnary(allowB: Boolean): (Double, Double) -> Double {
            if (position < expression.length && expression[position] == '+') {
                position++
                return parseUnary(allowB)
            }
            if (position < expression.length && expression[position] == '-') {
                position++
                val value = parseUnary(allowB)
                return { a, b -> -value(a, b) }
            }
            return parsePrimary(allowB)
        }

        private fun parsePrimary(allowB: Boolean): (Double, Double) -> Double {
            require(position < expression.length) { "Unexpected end of expression." }
            val ch = expression[position]
            if (ch == '(') {
                position++
                val value = parseAddSub(allowB)
                require(position < expression.length && expression[position] == ')') { "Missing closing parenthesis." }
                position++
                return value
            }
            if (ch == 'A') { position++; return { a, _ -> a } }
            if (ch == 'B') {
                require(allowB) { "Expression uses B, but no second raster is selected." }
                position++
                return { _, b -> b }
            }
            val start = position
            while (position < expression.length && (expression[position].isDigit() || expression[position] == '.' ||
                expression[position] == 'E' || expression[position] == 'e' ||
                ((expression[position] == '+' || expression[position] == '-') && position > start &&
                    (expression[position - 1] == 'E' || expression[position - 1] == 'e')))) position++
            require(position > start) { "Invalid token at position ${position + 1}." }
            val number = expression.substring(start, position).toDoubleOrNull()
                ?: error("Invalid number in raster expression.")
            return { _, _ -> number }
        }
    }

    /** Generate terrain derivatives from an elevation raster using GDAL DEMProcessing. */
    fun terrainRaster(inputPath: String, outputPath: String, operation: String, scale: Double = 1.0) {
        initialize()
        val algorithm = when (operation.lowercase()) {
            "slope" -> "slope"
            "aspect" -> "aspect"
            "hillshade" -> "hillshade"
            else -> error("Choose Slope, Aspect or Hillshade.")
        }
        require(scale > 0.0 && scale.isFinite()) { "Scale must be a positive number." }
        val source = gdal.Open(inputPath, gdalconstConstants.GA_ReadOnly)
            ?: error("Could not open input elevation raster.")
        try {
            val args = java.util.Vector<String>()
            if (algorithm == "slope") args.addAll(listOf("-s", scale.toString(), "-compute_edges"))
            if (algorithm == "aspect") args.addAll(listOf("-compute_edges"))
            if (algorithm == "hillshade") args.addAll(listOf("-z", "1", "-s", scale.toString(), "-az", "315", "-alt", "45", "-compute_edges"))
            val options = org.gdal.gdal.DEMProcessingOptions(args)
            val output = gdal.DEMProcessing(outputPath, source, algorithm, "", options)
                ?: error("GDAL $algorithm processing failed.")
            output.delete()
        } finally { source.delete() }
    }

    /** Clip a raster to an axis-aligned bounding box in the source raster CRS. */
    fun clipRasterByExtent(inputPath: String, outputPath: String, minX: Double, minY: Double, maxX: Double, maxY: Double) {
        initialize()
        require(listOf(minX, minY, maxX, maxY).all { it.isFinite() } && maxX > minX && maxY > minY) {
            "Enter a valid extent: minimum coordinates must be smaller than maximum coordinates."
        }
        val source = gdal.Open(inputPath, gdalconstConstants.GA_ReadOnly) ?: error("Could not open input raster.")
        try {
            val args = java.util.Vector<String>().apply {
                addAll(listOf("-of", "GTiff", "-te", minX.toString(), minY.toString(), maxX.toString(), maxY.toString()))
            }
            val options = org.gdal.gdal.WarpOptions(args)
            try {
                val output = gdal.Warp(outputPath, arrayOf(source), options)
                    ?: error("Raster clipping failed. Check that the extent overlaps the source raster.")
                output.delete()
            } finally { options.delete() }
        } finally { source.delete() }
    }

    /** Resample a raster to a target pixel size using GDAL Warp. */
    fun resampleRaster(inputPath: String, outputPath: String, pixelWidth: Double, pixelHeight: Double, method: String) {
        initialize()
        require(pixelWidth > 0.0 && pixelHeight > 0.0 && pixelWidth.isFinite() && pixelHeight.isFinite()) {
            "Pixel dimensions must be positive finite numbers."
        }
        val algorithm = when (method.lowercase()) {
            "nearest" -> "near"
            "bilinear" -> "bilinear"
            "cubic" -> "cubic"
            "average" -> "average"
            else -> error("Resampling method must be Nearest, Bilinear, Cubic or Average.")
        }
        val source = gdal.Open(inputPath, gdalconstConstants.GA_ReadOnly) ?: error("Could not open input raster.")
        try {
            val args = java.util.Vector<String>().apply {
                addAll(listOf("-of", "GTiff", "-tr", pixelWidth.toString(), pixelHeight.toString(), "-r", algorithm))
            }
            val options = org.gdal.gdal.WarpOptions(args)
            try {
                val output = gdal.Warp(outputPath, arrayOf(source), options)
                    ?: error("Raster resampling failed.")
                output.delete()
            } finally { options.delete() }
        } finally { source.delete() }
    }

    fun describeRaster(info: RasterInfo): String =
        "${info.width} × ${info.height} px · ${info.bands} band(s) · ${info.dataType}"
}
