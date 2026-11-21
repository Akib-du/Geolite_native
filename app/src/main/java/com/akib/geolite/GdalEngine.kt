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
 * GDAL 3.7.0 Android build: arm64-v8a and x86_64; drivers include
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

                val bandCount = minOf(warped.GetRasterCount(), 3)
                require(bandCount > 0) { "Raster has no displayable bands." }
                val translateArgs = java.util.Vector<String>().apply {
                    addAll(listOf("-of", "PNG", "-ot", "Byte"))
                    for (bandIndex in 1..bandCount) {
                        val band = warped.GetRasterBand(bandIndex)
                            ?: error("Could not read raster band $bandIndex.")
                        val stats = band.GetStatistics(0, 1)
                            ?: error("Could not calculate display statistics for raster band $bandIndex.")
                        var min = stats[0]
                        var max = stats[1]
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

    fun describeRaster(info: RasterInfo): String =
        "${info.width} × ${info.height} px · ${info.bands} band(s) · ${info.dataType}"
}
