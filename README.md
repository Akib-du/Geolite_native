# GeoLite Native

A native Android GIS application for phones and landscape tablets. This repository is separate from GeoLite V2.

## Current foundation (v0.1.0)

- Kotlin + Jetpack Compose Android application (no WebView map).
- MapLibre Native map renderer with online OpenFreeMap Liberty and Positron styles.
- GeoJSON Feature and FeatureCollection import with basic point, line, and polygon rendering.
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

1. Native GDAL/OGR + PROJ integration and driver verification (GeoPackage, Shapefile, GeoTIFF, OpenFileGDB).
2. Real dataset loading, layer rendering, identify and attribute table.
3. Vector editing and export.
4. Raster display and processing (reproject, clip, resample, calculator).
5. Spatial analysis tools and project persistence.

**Current data-reader scope:** GeoJSON Feature and FeatureCollection files can be parsed and rendered as basic point, line, and polygon layers. Other formats in the roadmap (including Shapefile, GeoPackage, GeoTIFF, and FileGDB) are not yet supported. Attribute tables, layer styling controls, raster rendering, GDAL/OGR, and PROJ are not yet integrated. Basemap services have separate usage terms and attribution requirements.
