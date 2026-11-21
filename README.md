# GeoLite Native

Native Android GIS application built with Kotlin, Jetpack Compose, MapLibre Native, and GDAL/OGR. This repository is separate from `GeoLite_V2`.

## Data formats and map display

- **Shapefile:** open a folder containing the complete `.shp/.shx/.dbf` set, or open a ZIP containing the set. Folder import preserves companion files.
- **File Geodatabase:** select the `.gdb` folder. The app checks the packaged `OpenFileGDB` driver, lists feature classes, and lets the user load each class. GDAL 3.7 OpenFileGDB access is read-only in this app.
- **GPX:** reads waypoint, route, track and related layers when the packaged GDAL GPX driver is available. The layer chooser lists the available GPX layers.
- **GeoTIFF:** opens raster metadata and renders a georeferenced map preview. GDAL warps the preview to WGS84 and scales its width to 2048 pixels for mobile display. Original raster data is retained for reprojection.
- **Runtime driver status:** the Catalog pane reports whether the packaged GDAL runtime actually exposes SHP, FileGDB, GPX and GTiff drivers.

The CI workflow builds the GDAL Android AAR from source with GPX, OpenFileGDB and PNG drivers explicitly enabled. Driver availability is still checked at runtime; the app does not assume that a build flag guarantees a working driver.

## Basemaps and map elements

- OpenStreetMap raster tiles.
- Esri World Imagery satellite tiles.
- OpenFreeMap Liberty and Positron styles.
- No basemap.
- Vector symbology controls: color, line width, polygon opacity, point icon (pin/circle/star/square), icon size and icon anchor/position; raster opacity is adjustable too.
- A dynamic on-map scale division in metres/kilometres, with visibility and corner-position controls.
- Map click/long-press placemarks, coordinate-based placemarks and GPS placemarks. GPS requests Android location permission at runtime.
- On-map Identify and Select tools query rendered vector features and show their attributes; Select adds a highlight overlay.

## Tools and editing

- Raster reprojection tool with a user-entered target CRS (for example `EPSG:32646`); output is GeoTIFF in app-specific GeoLite/Processing storage.
- Initial vector buffer operation.
- Create WGS84 Shapefile feature classes with Point, Line or Polygon geometry.
- Point digitizing into the active point Shapefile by tapping the map in Edit point mode.
- Add attribute fields (String, Integer, Real or Date), edit field values for a selected feature, and delete selected features.
- Attribute table supports selecting a feature and editing individual field values.

**Editing scope:** write operations currently target Shapefiles. FileGDB and GPX are treated as read-only; the current FileGDB driver/build and workflow do not provide a complete editable FileGDB geodatabase experience. Map-click digitizing currently creates Point features only; line and polygon feature classes can be created but their drawing/editing tools are not yet implemented. The attribute table is currently a mobile inspection/editor for the selected layer, not a full desktop spreadsheet replacement.

## Important limitations / testing status

- Large vector layers are converted to in-memory GeoJSON for display and can consume substantial memory. Streaming, paging and spatial filtering are future work.
- Raster preview is a single georeferenced image overlay, not a tiled raster renderer; very large or rotated/complex rasters may need preprocessing.
- Raster processing currently includes reprojection; raster calculator, clip, resampling controls, multi-band renderer and raster symbology are pending.
- Vector processing currently includes buffer; clip, dissolve, overlay, spatial join and topology tools are pending.
- Reprojection UI currently targets raster inputs; vector reprojection UI is pending.
- Physical-device testing and validation against sample SHP, FileGDB, GPX and TIFF datasets are required before describing the app as production-ready.

## Build

GitHub Actions builds a debug APK and publishes it as the `GeoLite-Native-debug-apk` artifact after the custom GDAL Android engine and app build succeed.

Local Android app build (after placing a compatible GDAL AAR at `app/libs/gdal-3.7.0.aar`):

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.
