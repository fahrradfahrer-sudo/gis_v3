# Witt3D_GIS - User Manual (English)

## 1. Introduction & Application Overview

**Witt3D_GIS** is a mobile Geographic Information System (GIS) and High-Precision Surveying application designed for field engineers, surveyors, and GIS specialists.

### Key Capabilities
* **High-Precision GNSS Positioning:** Connects to external GNSS receivers (e.g., u-blox F9P) via USB OTG with native support for NMEA sentences and binary `UBX NAV-PVT` protocols.
* **NTRIP RTK Differential Correction Client:** Built-in NTRIP client streams RTCM3 corrections from reference networks to achieve centimeter- or millimeter-level RTK positioning (Fix/Float status).
* **Interactive Map & Layer Management:** Renders vector tiles, Google Earth Hybrid satellite imagery, high-contrast debug basemaps, and custom external Web Map Service (WMS) layers up to extreme zoom scales (down to 1:5 scale / zoom level 24.0+).
* **Feature Digitizing & Editing:** Create and edit Points, LineStrings, and Polygons with snapping capabilities, metadata notes, interactive map relocation, and precise relocation by exact distance and bearing angle.
* **Measurement & Target Navigation:** Distance measurement tool with a dynamic off-screen target arrow pointing towards active survey points with real-time distance updates.
* **Multi-Format GIS Import & Export:** Export collected field data directly to ESRI Shapefile ZIP bundles (`.shp`, `.shx`, `.dbf`), GeoJSON, KML, QGZ, and CSV formats.
* **Bilingual UI:** Native support for English and German UI localizations.

---

## 2. Screen Layout & Interface Overview

The interface is organized into four main functional regions:

### 2.1 Top Status & Control Bar
* **Baud Rate Spinner:** Dropdown list to select GNSS serial baud rates (4800 to 921600 bps; default: **115200**).
* **Connect / Disconnect Button:** Initiates or terminates the serial connection to the USB GNSS hardware.
* **RTK Fix Status Indicator:** Displays real-time GNSS fix quality (`NO FIX`, `SINGLE`, `FLOAT`, or `FIX`).
* **Sats Count (`Sats: X`):** Shows the current number of tracked positioning satellites.
* **Age of Differential (`Age: X`):** Shows the age of RTCM differential corrections in seconds.
* **Altitude (`Alt: X`):** Displays real-time orthometric/ellipsoidal height in meters.
* **Follow Button (`Follow`):** Centers and locks the map view onto the live GNSS crosshair position.
* **Measure Button (`Meas`):** Toggles interactive distance measurement mode.
* **Set Point Button (`Set`):** Sets the current map center or GNSS coordinate as the active measurement target.

### 2.2 Secondary Drawing Toolbar
Located immediately below the top status bar:
* **`Draw Pt` (Draw Point):** Activates manual point creation by tapping on the map.
* **`Add Pt @ GNSS`:** Instantly creates a point feature at the exact current GNSS location.
* **`Draw Line`:** Begins or completes drawing a multi-segment LineString feature.
* **`Draw Poly`:** Begins or completes drawing a closed Polygon feature.
* **`Edit`:** Toggles feature editing mode (tap any existing object to edit or delete).
* **`Clear`:** Clears active drawing points or distance measurement vectors.
* **`Snap` (Magnet Icon):** Toggles snapping mode to automatically snap new vertices to existing geometry points within a 10m radius.
* **Active Mode Styling:** When a tool is active, the corresponding button highlights in **RAL 5005 Signal Blue** (`#00538E`) with white text.

### 2.3 Map Canvas & Overlay HUD
* **Center Coordinates HUD:** Real-time latitude and longitude of the map center displayed in the bottom-right corner (updated continuously during map panning/zooming).
* **Scale Bar & Relative Scale:** Displays an accurate MapLibre scale bar in the top-left corner alongside the dynamic relative scale ratio (e.g., `1:250`).
* **Off-Screen Distance Arrow:** When a measurement target point is navigated off-screen, an edge arrow appears on the screen boundary pointing toward the target with live distance updates in meters.

### 2.4 Left Navigation Drawer (GIS Menu)
Accessed via the hamburger menu icon in the top-left corner:
* **GIS Menu / Layers:** Toggle visibility of GNSS Crosshair and Manual Geometries.
* **WMS Layers:** Add, edit, or toggle external raster WMS layers.
* **Import Data:** Import CSV or GIS files (`.shp`, `.json`, `.qgz`, `.kml`).
* **Export Data:** Export field data in Shapefile ZIP, GeoJSON, KML, QGZ, or CSV format.
* **Display Settings:** Toggle Relative Scale indicator, Diagnostic Isolation Mode, or switch app language between English and German.
* **Manual Geometries List:** An expandable tree list of all digitized features with names and notes. Tapping any feature zooms the map directly to its location (`zoomToFeature`).

---

## 3. GNSS Receiver Connection & Hardware Setup

### 3.1 USB OTG Hardware Connection
1. Connect your external GNSS receiver (e.g., u-blox ZED-F9P via USB OTG adapter) to your device.
2. Grant the USB device permission prompt when requested.
3. Select the receiver's serial baud rate from the top dropdown list (Default: **115200**).
4. Tap **Connect**. Upon successful connection, the status banner updates to `Serial Connected`.

### 3.2 Dual Protocol Support (NMEA & UBX NAV-PVT)
* Witt3D_GIS automatically parses standard NMEA GGA/RMC sentences as well as high-frequency binary u-blox **UBX NAV-PVT** packets.
* Binary UBX data provides millisecond-accurate timestamping, precise RTK fix determination, carrier phase solution tracking, and raw satellite count.

### 3.3 Skyplot & Satellite Signal Status
1. Open the Navigation Drawer and tap **Settings** / **NTRIP Settings**.
2. Tap the **Skyplot** button.
3. The Skyplot window renders a polar chart showing satellite positions (Azimuth and Elevation) along with PRN identifiers and Signal-to-Noise Ratio (SNR) signal strength bars.

---

## 4. NTRIP RTK Differential Correction Setup

To achieve centimeter-level positioning accuracy, configure the built-in NTRIP client:

1. Open the left menu and select **Settings** -> **NTRIP Settings**.
2. Enter your RTK correction provider details:
   * **NTRIP Host:** IP address or hostname of the caster (e.g., `rtk.provider.com`).
   * **NTRIP Port:** Caster port (typically `2101` or `8080`).
   * **NTRIP Mountpoint:** Mountpoint identifier (e.g., `VRS_3_2_DE`).
   * **NTRIP User:** Your account username.
   * **NTRIP Pass:** Your account password.
3. **Manual Position Option:** If live position is unavailable, check **Manual Position** to send static GGA coordinates to the caster.
4. Tap **Save & Start NTRIP**.
5. Once connected, RTCM3 correction data streams directly over the socket to your receiver. Watch the top status bar transition from `SINGLE` -> `FLOAT` -> **`FIX`**.

---

## 5. Map Styles & Raster WMS Layers

### 5.1 Basemap Selection
The application includes multiple pre-configured map styles:
* **Google Earth Hybrid (Default):** High-resolution satellite imagery overlaid with road network and place labels.
* **High Contrast (Yellow):** Bright yellow background (#FFFF00) optimized for extreme sunlight visibility and geometry isolation debugging.
* Standard vector street map styles.

### 5.2 WMS (Web Map Service) Configuration
1. Open the left menu and tap **WMS Layers**.
2. Tap **Add WMS Layer**.
3. Enter the WMS endpoint URL (e.g., `https://geodaten.land.de/wms`) and layer names (comma-separated).
4. Save the configuration. External WMS layers are rendered beneath manual vector geometries at max zoom levels up to 35.0f, ensuring raster imagery never disappears when zooming in close.

---

## 6. Digitizing & Editing Geometries

### 6.1 Creating Points
* **Manual Map Tap (`Draw Pt`):** Tap `Draw Pt`, then tap anywhere on the map. Enter a feature Name and Notes, then tap **Save**.
* **Instant Point at Receiver (`Add Pt @ GNSS`):** Tap `Add Pt @ GNSS`. A point feature is immediately created at the current GNSS coordinate.

### 6.2 Drawing Lines & Polygons
1. Tap `Draw Line` or `Draw Poly`.
2. Tap sequential locations on the map to place vertices. A temporary red line or polygon is rendered in real time.
3. Tap `Draw Line` or `Draw Poly` again to finish digitizing.
4. Enter the Name and Notes in the prompt dialog and tap **Save**. Saved features render in Magenta.

### 6.3 Vertex Snapping Mode (`Snap`)
* Tap the magnet icon (`Snap`) on the drawing bar to enable snapping mode.
* When placing a new point or moving a vertex, the cursor automatically snaps to the exact coordinates of any existing feature vertex within a 10-meter radius.

### 6.4 Editing Features
1. Tap the `Edit` button on the drawing toolbar.
2. Tap any feature on the map to open the **Feature Properties Dialog**.
3. Available Actions:
   * **Save:** Update Name and Notes.
   * **Delete:** Permanently remove the feature.
   * **Move:** Tap `Move`, then tap a new position on the map to relocate the object.
   * **Move By...:** Enter an exact distance (meters) and bearing angle (0–360°) to shift the feature with millimeter precision.

---

## 7. Measurement Tool & Target Navigation

1. Tap `Meas` on the top bar to enter Measurement Mode.
2. Tap points on the map to measure linear distances between vertices.
3. Tap `Set` to establish a survey target point.
4. **Off-Screen Target Arrow:** If you navigate or walk away from the target point such that it leaves the screen, a dynamic navigation arrow (`ic_nav_arrow`) appears on the screen boundary pointing toward the target and displaying real-time distance in meters.

---

## 8. Data Import & Export

### 8.1 Importing GIS Data
1. Open the left menu and tap **Import Data**.
2. Select **Import CSV** (Name, Lat, Lon) or **Import GIS File** (`.shp`, `.json`, `.qgz`, `.kml`).
3. Browse and select the file using the system file picker. Features are imported and displayed directly on the map.

### 8.2 Exporting GIS Data
1. Open the left menu and tap **Export Data**.
2. Choose your preferred export format:
   * **Shapefile (.zip):** Generates a complete ZIP archive containing `.shp`, `.shx`, and `.dbf` files formatted to ESRI standards.
   * **GeoJSON (.json)**
   * **KML (.kml)**
   * **QGIS Project (.qgz)**
3. Use the system file dialog to save the exported file to your device or external SD card.

---

## 9. Language & Display Settings

* **Language Switcher:** Open the left menu and tap **Language** (DE / EN) to toggle the interface between German and English. Setting is saved in app preferences.
* **Relative Scale Indicator:** Displays dynamic real-time map scale (e.g., `1:500`) recalculated based on screen pixel density and zoom level.
