package com.akib.geolite

import org.gdal.gdal.gdal
import org.gdal.gdalconst.gdalconstConstants
import org.gdal.ogr.ogr
import org.gdal.osr.SpatialReference
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
            require(layerIndex in 0 until dataSource.GetLayerCount()) { "Vector layer index is out of range." }\n            val layer = dataSource.GetLayer(layerIndex) ?: error("Could not read vector layer $layerIndex.")
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
                val properties = JSONObject()
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
