# GeoLite Native

A native Android GIS application for phones and landscape tablets. This repository is separate from GeoLite V2.

## Current development build

- Kotlin + Jetpack Compose Android application with MapLibre Native map rendering.
- Online OpenFreeMap Liberty and Positron basemaps.
- GDAL 3.7.0 / PROJ 9.2.1 Android AAR downloaded by CI.
- GeoJSON, KML and Shapefile ZIP vector import, with coordinate conversion to WGS 84 for map display.
- GeoTIFF, Erdas IMG and VRT raster metadata inspection. Raster drawing is not implemented yet.
- OGR vector buffer output to GeoJSON and GDAL Warp raster reprojection to GeoTIFF.
- Attribute inspection for the most recently loaded vector feature class.
- Android Storage Access Framework file and folder selection, GPS placemarks and long-press manual placemarks.

## File Geodatabase (.gdb)

The app now includes a folder picker and a FileGDB import flow. It copies the selected `.gdb` folder into app-private cache, checks whether the bundled GDAL runtime exposes the `OpenFileGDB` driver, lists feature classes, and lets the user choose a feature class to load.

**Important:** this is conditional support until the Android AAR has been verified on a real FileGDB dataset and the app on a physical Android device. The app will report a clear error if `OpenFileGDB` is absent from the packaged GDAL build. GDAL's OpenFileGDB driver is built-in by default in standard GDAL builds and provides vector access without the proprietary Esri FileGDB SDK, but a third-party Android binary may have a different driver configuration. See the [GDAL OpenFileGDB documentation](https://gdal.org/en/stable/drivers/vector/openfilegdb.html).

The current FileGDB loader converts a selected feature class to an in-memory GeoJSON FeatureCollection for MapLibre. Very large feature classes may therefore require paging/streaming improvements before they are practical on lower-memory devices. It is currently a read/display workflow, not full FileGDB editing.

## Known limitations

- FileGDB support depends on the OpenFileGDB driver actually being present in the packaged AAR; runtime detection is included, but a sample geodatabase/device test is still required.
- GeoPackage support has not been verified or enabled.
- Raster datasets can be inspected but are not yet rendered as map overlays.
- Raster processing UI is limited to reprojection; raster calculator, clip, resampling controls and raster symbology remain pending.
- Vector buffer is the initial vector processing operation; broader overlay, dissolve, clip and editing tools remain pending.
- The attribute table currently displays the latest imported vector layer only.
- Physical tablet testing has not yet been completed.

## Build

Open this repository in Android Studio (JDK 17, Android SDK 36), or run:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds a debug APK on pushes to `main` and uploads it as the `GeoLite-Native-debug-apk` artifact when the build succeeds.
