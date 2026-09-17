# Witt3D_GIS - Windows Desktop Porting & Architecture Guide

## Executive Summary

This document provides a technical blueprint and architectural roadmap for porting **Witt3D_GIS** from Android to a native **Windows Desktop application**.

Witt3D_GIS is currently built as an Android application using Kotlin, MapLibre Android SDK (`org.maplibre.android`), `usb-serial-android` for OTG hardware GNSS receiver communication, custom NTRIP socket client management, Android XML layout views, and Android Storage Access Framework (SAF).

Porting Witt3D_GIS to native Windows allows field surveyors and GIS technicians to run the full application suite—including high-precision RTK GNSS tracking, manual geometry digitizing, WMS layer integration, and Shapefile/KML export—on Windows tablets, laptops, and rugged field computers (e.g., Panasonic Toughbook, Microsoft Surface).

---

## 1. Existing Android Architecture Analysis

| Subsystem | Current Android Implementation | Primary Android Dependency |
| :--- | :--- | :--- |
| **User Interface** | XML Layouts (`activity_main.xml`), `DrawerLayout`, `NavigationView`, `AlertDialog` | `androidx.appcompat`, `com.google.android.material` |
| **Map Engine** | MapLibre Android SDK (`MapLibreMap`, `MapView`, `GeoJsonSource`, `SymbolLayer`, `FillLayer`, `LineLayer`) | `org.maplibre.android:android-sdk` (C++ JNI `.so`) |
| **Serial / GNSS** | `usb-serial-android` driver via USB OTG (`UsbManager`) | `com.hoho.android.usbserial` |
| **GNSS Parsing** | Binary UBX `NAV-PVT` parser & NMEA parser in `SerialLocationManager.kt` | Pure Kotlin (Byte array operations) |
| **NTRIP RTK Client** | Socket client sending GGA & receiving RTCM3 stream in `NtripManager.kt` | Pure Kotlin / `java.net.Socket` |
| **Custom Canvas Views** | `SkyplotView.kt` (satellite polar plot) & `ScaleBarView.kt` | `android.graphics.Canvas`, `android.view.View` |
| **Data Persistence** | `SharedPreferences` (`app_prefs`, JSON string serialization) | `android.content.SharedPreferences` |
| **File I/O / SAF** | Storage Access Framework (`ActivityResultLauncher`, `ContentResolver`, `Uri`) | `android.provider.DocumentsContract` |

---

## 2. Target Technology Stack Recommendation

To maximize code reuse from the existing Kotlin implementation, the recommended tech stack for native Windows desktop is:

* **Primary Recommendation: Kotlin Multiplatform (KMP) + Compose Multiplatform for Desktop (JVM)**
  * **Language:** 100% Kotlin (17+ JVM Target).
  * **UI Toolkit:** Compose Multiplatform Desktop (Skia-based rendering on Windows via Direct3D/OpenGL).
  * **Build System:** Gradle (`build.gradle.kts` with desktop target plugin).
  * **Packaging Tool:** Gradle `jpackage` / `Conveyor` (generates native `.exe` installers or `.msi` packages bundled with a embedded lightweight JVM).

* **Alternative Option: JavaFX with Kotlin (TornadoFX / OpenJFX)**
  * Suitable if traditional desktop windowing, dockable panes, or classic desktop toolbars are preferred over declarative UI.

---

## 3. Component-by-Component Migration Strategy

### 3.1 Map Engine Migration (MapLibre Android -> MapLibre Desktop)

* **Challenge:** The MapLibre Android SDK relies on native C++ libraries (`libmapbox-gl.so`) compiled for Android JNI and renders into Android `SurfaceView` / `TextureView`. It cannot be directly included in a desktop JVM project.
* **Recommended Solution: Embedded MapLibre GL JS via Chromium Webview (CEF / KCEF)**
  * **Implementation:** Embed a high-performance, hardware-accelerated Chromium web view using `KCEF` (Kotlin Chromium Embedded Framework) or JavaFX `WebView` inside the Compose Desktop / JavaFX window.
  * **Mechanism:** Load a local HTML/JS bundle containing MapLibre GL JS (`maplibre-gl.js`).
  * **Bridge:** Communicate between Kotlin desktop code and MapLibre GL JS via JS Interop / WebSockets / local REST API.
  * **Benefits:**
    * 100% feature parity with vector tile styles, raster WMS layers, GeoJSON sources, line/fill/symbol styling, and high-zoom rendering up to maxZoom 28.
    * Identical map style JSON support (including MapTiler, Google Hybrid raster tile sources, and local WMS endpoint layers).
    * Smooth hardware-accelerated 60 FPS map panning, vector drawing, and real-time GNSS crosshair rendering.

---

### 3.2 Serial Hardware Connection (USB GNSS Receivers)

* **Challenge:** `usb-serial-android` depends on Android's `UsbManager` and `UsbDeviceConnection` permission model, which does not exist on Windows.
* **Recommended Solution: `jSerialComm` (`com.fazecast:jSerialComm`)**
  * `jSerialComm` is a robust, lightweight, pure Java/Kotlin library with native Windows DLL support for serial ports (COM1, COM2, USB-to-Serial FTDI/u-blox chips).
  * **Code Porting:**
    ```kotlin
    // Replacing UsbSerialPort with jSerialComm SerialPort
    val comPorts = SerialPort.getCommPorts()
    val serialPort = SerialPort.getCommPort("COM3") // or user selected port
    serialPort.setBaudRate(115200)
    serialPort.openPort()

    serialPort.addDataListener(object : SerialPortDataListener {
        override fun getListeningEvents(): Int = SerialPort.LISTENING_EVENT_DATA_AVAILABLE
        override fun serialEvent(event: SerialPortEvent) {
            val bytesAvailable = serialPort.bytesAvailable()
            val buffer = ByteArray(bytesAvailable)
            serialPort.readBytes(buffer, bytesAvailable.toLong())
            // Pass byte array directly into existing UBX & NMEA parsers!
            serialLocationManager.processBytes(buffer)
        }
    })
    ```
  * **Code Reuse:** The binary `UBX NAV-PVT` byte extraction logic, checksum verification, and `NMEA` string parsers in `SerialLocationManager.kt` are 100% reusable without modification.

---

### 3.3 NTRIP RTK Differential Correction Client

* **Current Implementation:** `NtripManager.kt` opens a TCP socket connection to an NTRIP Caster (host, port, mountpoint, basic auth), sends GGA position strings, and streams RTCM3 binary correction bytes.
* **Desktop Migration:** `NtripManager.kt` uses standard `java.net.Socket` and Kotlin Coroutines/Threads. It is **100% portable to Windows Desktop with zero changes to socket logic**.
* The RTCM3 correction payload read from the NTRIP socket is written directly to the `jSerialComm` serial output stream (`serialPort.writeBytes(rtcmBytes, rtcmBytes.size)`), sending RTK corrections back to the u-blox receiver for millimeter-level positioning.

---

### 3.4 Custom Canvas Views (`SkyplotView` & `ScaleBarView`)

* **Current Implementation:** `SkyplotView.kt` overrides `onDraw(canvas: Canvas)` to draw polar coordinate satellite plots (azimuth/elevation circles, satellite PRNs, SNR signal strength bars).
* **Desktop Migration (Compose Multiplatform):**
  * Convert `onDraw` logic to Compose Desktop `Canvas(modifier = Modifier.fillMaxSize()) { drawCircle(...); drawText(...) }`.
  * The polar math calculations (`radius * cos(elevation) * sin(azimuth)`) remain identical.

---

### 3.5 Data Storage, Configuration & File I/O

* **Configuration Settings:** Replace `SharedPreferences` with Java Preferences (`java.util.prefs.Preferences`) or a lightweight JSON configuration file saved at `%APPDATA%\Witt3D_GIS\config.json`.
* **File Import/Export:**
  * Replace Android SAF (`Intent.ACTION_CREATE_DOCUMENT`) with standard native Windows file dialogs using `JFileChooser` or `TinyFileDialogs`.
  * **Shapefile / KML / GeoJSON / QGZ Generation:** The underlying shapefile byte packing logic (writing `.shp`, `.shx`, `.dbf` binary headers and record headers) and ZIP archive creation are standard Kotlin/Java I/O operations and remain 100% compatible with Windows Desktop.

---

## 4. Step-by-Step Phased Roadmap

```
+-----------------------------------------------------------------------------------+
| Phase 1: Shared Core Extraction (KMP commonMain)                                 |
| - Extract UBX/NMEA parsing, NTRIP client, GeoJSON models, & Geometry algorithms. |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
| Phase 2: Windows Hardware Engine (jSerialComm Integration)                        |
| - Implement COM port enumeration, baud rate selection, and RTCM3 pass-through.    |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
| Phase 3: Desktop UI Development (Compose Multiplatform)                          |
| - Build Navigation Drawer, Drawing Toolbar, Measurement panel, & Dialogs.         |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
| Phase 4: Map Engine Integration (MapLibre GL JS Webview)                          |
| - Embed Chromium Webview, load MapLibre GL JS, & map bidirectional JS-Kotlin bridge. |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
| Phase 5: Packaging & Distribution (Gradle jpackage)                              |
| - Generate standalone Windows .exe installer and portable zip archive.             |
+-----------------------------------------------------------------------------------+
```

---

## 5. Summary Recommendation

Porting Witt3D_GIS to Windows as a native desktop application is fully feasible and architecturally straightforward. Because the core GNSS processing (UBX NAV-PVT parsing, NTRIP RTCM streaming, GeoJSON geometry math, shapefile zip generation) is written in clean Kotlin, over **60% of the existing codebase can be reused directly** in a Kotlin Multiplatform Desktop project.
