# GeoLite Native

A native Android GIS application for phones and landscape tablets. This repository is separate from GeoLite V2.

## Current native GIS integration (development build)

- Bundles the Android GDAL 3.7.0 / PROJ 9.2.1 AAR for ARM64 and x86_64. CI downloads the AAR at build time.
- OGR vector loading for GeoJSON, KML and Shapefile ZIP archives; projected vector coordinates are transformed to WGS 84 before MapLibre rendering.
- Raster dataset inspection for GeoTIFF, Erdas IMG and VRT, including dimensions, band count, data type and projection.
- OGR vector buffer processing with output to GeoJSON (distance is in the input layer's coordinate units).
- GDAL Warp raster reprojection to GeoTIFF (current UI target: EPSG:4326).
- Imported vector attributes remain inspectable in the table panel.

**Important limitations:** raster files can currently be opened and inspected in the layer catalogue, but are not yet drawn as map overlays. Raster processing currently exposes reprojection only; raster calculator, clipping, resampling controls and raster symbology are still pending. This GDAL AAR does not advertise GeoPackage or FileGDB drivers, so those formats are not claimed as supported. Shapefiles should be opened as a ZIP containing the .shp, .shx and .dbf sidecars (and preferably .prj). The app has not yet been verified on a physical tablet.

## Current foundation (v0.1.0)

- Kotlin + Jetpack Compose Android application (no WebView map).
- MapLibre Native map renderer with online OpenFreeMap Liberty and Positron styles.
- GeoJSON Feature and FeatureCollection import with basic point, line, and polygon rendering.
- Initial attribute inspection panel for imported GeoJSON feature properties (read-only; latest imported layer).
- Android Storage Access Framework file picker.
- Runtime location permission flow and current-location placemark.
- Long-press map to add manual placemarks.
- Tablet-oriented workspace with Catalog and Layers panels.
- ARM64-v8a build target (also includes armeabi-v7a and x86_64).

## Build

Open this repository in Android Studio (JDK 17, Android SDK 36), or run:

~~~bash
gradle assembleDebug
~~~

APK output: app/build/outputs/apk/debug/app-debug.apk.

## Roadmap

1. Expand GDAL driver coverage (GeoPackage and OpenFileGDB) and add runtime format tests.
2. Expand layer management, identify/select, and editable attribute tables across multiple layers.
3. Vector editing and export.
4. Raster map rendering and processing (clip, resample, calculator, symbology).
5. Spatial analysis tools and project persistence.

**Current data-reader scope:** GeoJSON Feature and FeatureCollection files can be parsed and rendered as basic point, line, and polygon layers. Other formats in the roadmap (including Shapefile, GeoPackage, GeoTIFF, and FileGDB) are not yet supported. The attribute panel currently inspects properties from the latest imported GeoJSON layer only; it is read-only and is not yet a full multi-layer GIS attribute table. Layer styling controls, raster rendering, GDAL/OGR, and PROJ are not yet integrated. Basemap services have separate usage terms and attribution requirements.
