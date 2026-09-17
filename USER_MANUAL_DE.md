# Witt3D_GIS - Benutzerhandbuch (Deutsch)

## 1. Einführung & Anwendungsübersicht

**Witt3D_GIS** ist eine mobile Geoinformationssystem- (GIS) und hochpräzise Vermessungsanwendung, die für Feldingenieure, Vermessungstechniker und GIS-Spezialisten entwickelt wurde.

### Hauptfunktionen
* **Hochpräzise GNSS-Positionierung:** Verbindet sich über USB-OTG mit externen GNSS-Empfängern (z. B. u-blox F9P) mit nativer Unterstützung für NMEA-Sätze sowie binäre `UBX NAV-PVT`-Protokolle.
* **NTRIP-RTK-Korrekturdienst-Client:** Der integrierte NTRIP-Client streamt RTCM3-Korrekturdaten von Referenznetzwerken, um zentimeter- oder millimetergenaue RTK-Positionierung zu erreichen (Fix/Float-Status).
* **Interaktive Karte & Ebenen-Verwaltung:** Rendert Vektortiles, Google Earth Hybrid-Satellitenbilder, hochkontrastreiche Debug-Karten sowie externe Web Map Service (WMS)-Ebenen bis zu extremen Zoomstufen (bis zum Maßstab 1:5 / Zoomstufe 24,0+).
* **Objekterfassung & -bearbeitung:** Erfassung und Bearbeitung von Punkten, Linien und Polygonen mit Fangfunktion (Snapping), Notizen, interaktivem Verschieben auf der Karte sowie präzisem Verschieben nach Entfernung und Peilung.
* **Messwerkzeug & Ziel-Navigation:** Entfernungsmesswerkzeug mit dynamischem Ziel-Pfeil am Bildschirmrand, der kontinuierlich auf aktive Vermessungspunkte zeigt und die Entfernung in Echtzeit aktualisiert.
* **Multi-Format GIS-Import & -Export:** Exportiert erfasste Felddaten direkt in ESRI Shapefile-ZIP-Archive (`.shp`, `.shx`, `.dbf`), GeoJSON, KML, QGZ und CSV-Formate.
* **Zweisprachige Benutzeroberfläche:** Native Unterstützung für Deutsch und Englisch.

---

## 2. Bildschirmlayout & Bedienoberfläche

Die Benutzeroberfläche ist in vier Hauptbereiche unterteilt:

### 2.1 Obere Status- & Steuerungsleiste
* **Baudraten-Auswahl:** Dropdown-Liste zur Auswahl der seriellen GNSS-Baudrate (4800 bis 921600 bps; Standard: **115200**).
* **Verbinden / Trennen Button:** Startet oder beendet die serielle Verbindung zum USB-GNSS-Empfänger.
* **RTK-Statusanzeige:** Zeigt die Echtzeit-GNSS-Lösungsqualität an (`Kein Fix` / `NO FIX`, `SINGLE`, `FLOAT` oder `FIX`).
* **Satellitenanzahl (`Sats: X`):** Zeigt die Anzahl der aktuell empfangenen Satelliten an.
* **Korrektur-Alter (`Alter: X`):** Zeigt das Alter der RTCM-Differentialkorrekturen in Sekunden an.
* **Höhe (`Höhe: X`):** Zeigt die Echtzeit-Höhe in Metern an.
* **Folgen Button (`Folgen`):** Zentriert und fixiert den Kartenausschnitt auf das aktuelle GNSS-Fadenkreuz.
* **Messen Button (`Messen`):** Aktiviert den interaktiven Entfernungsmessmodus.
* **Set Button (`Set`):** Setzt das aktuelle Kartenzentrum oder die GNSS-Koordinate als aktives Messziel.

### 2.2 Zweite Zeichen-Werkzeugleiste
Befindet sich direkt unter der oberen Statusleiste:
* **`Pkt Zeichnen`:** Aktiviert die manuelle Punkterfassung durch Tippen auf die Karte.
* **`Pkt @ GNSS`:** Erstellt sofort ein Punktobjekt an der exakten aktuellen GNSS-Position.
* **`Linie Zeichnen`:** Startet oder beendet das Zeichnen einer Mehrpunkt-Linie.
* **`Poly Zeichnen`:** Startet oder beendet das Zeichnen eines geschlossenen Polygons.
* **`Bearbeiten`:** Aktiviert den Bearbeitungsmodus (Objekt auf der Karte angetippt zum Bearbeiten oder Löschen).
* **`Löschen` / `Clear`:** Löscht aktive Zeichenpunkte oder Messvektoren.
* **`Fang` (Magnet-Symbol):** Schaltet den Fangmodus ein/aus, um neue Punkte automatisch an bestehende Geometrieknoten im Umkreis von 10 m anzupassen.
* **Aktive Werkzeug-Hervorhebung:** Bei aktivem Werkzeug wird der entsprechende Button in **RAL 5005 Signalblau** (`#00538E`) mit weißer Schrift hervorgehoben.

### 2.3 Kartenbereich & HUD-Overlay
* **Zentrums-Koordinaten HUD:** Echtzeit-Breiten- und Längengrad des Kartenzentrums unten rechts im Bildschirm (wird beim Verschieben/Zoomen kontinuierlich aktualisiert).
* **Maßstabsbalken & Relativer Maßstab:** Zeigt einen präzisen MapLibre-Maßstabsbalken oben links zusammen mit dem relativen Maßstab (z. B. `1:250`) an.
* **Ziel-Pfeil außerhalb des Bildschirms:** Wenn ein Messziel außerhalb des sichtbaren Kartenausschnitts liegt, erscheint am Bildschirmrand ein Navigationspfeil, der auf das Ziel zeigt und die Entfernung in Metern anzeigt.

### 2.4 Linkes Navigationsmenü (GIS-Menü)
Erreichbar über das Hamburger-Menüsymbol oben links:
* **GIS Menü / Ebenen:** Ein-/Ausblenden von GNSS-Fadenkreuz und manuellen Geometrien.
* **WMS Ebenen:** Hinzufügen, Bearbeiten oder Umschalten externer Raster-WMS-Ebenen.
* **Daten Importieren:** Import von CSV- oder GIS-Dateien (`.shp`, `.json`, `.qgz`, `.kml`).
* **Daten Exportieren:** Export von Felddaten als Shapefile-ZIP, GeoJSON, KML, QGZ oder CSV.
* **Anzeige-Einstellungen:** Umschalten von relativem Maßstab, Diagnose-Isolationsmodus oder Sprachwechsel zwischen Deutsch und Englisch.
* **Manuelle Geometrien Liste:** Eine erweiterbare Liste aller erfassten Objekte mit Namen und Notizen. Antippen eines Objekts führt `zoomToFeature` aus und zentriert die Karte direkt darauf.

---

## 3. GNSS-Empfänger-Verbindung & Hardware-Einrichtung

### 3.1 USB-OTG Hardware-Verbindung
1. Verbinden Sie Ihren externen GNSS-Empfänger (z. B. u-blox ZED-F9P über USB-OTG-Adapter) mit Ihrem Gerät.
2. Bestätigen Sie die USB-Berechtigungsanfrage.
3. Wählen Sie die Baudrate des Empfängers aus der oberen Liste aus (Standard: **115200**).
4. Tippen Sie auf **Verbinden**. Bei erfolgreicher Verbindung wechselt der Status zu `Seriell verbunden`.

### 3.2 Dual-Protokoll-Unterstützung (NMEA & UBX NAV-PVT)
* Witt3D_GIS verarbeitet automatisch Standard-NMEA GGA/RMC-Sätze sowie hochfrequente binäre u-blox **UBX NAV-PVT** Pakete.
* Binäre UBX-Daten bieten millisekunden-genaue Zeitstempel, präzise RTK-Lösungsbestimmung und Trägerphasen-Tracking.

### 3.3 Skyplot & Satellitensignal-Status
1. Öffnen Sie das linke Navigationsmenü und wählen Sie **Einstellungen** / **NTRIP Einstellung**.
2. Tippen Sie auf die Schaltfläche **Skyplot**.
3. Das Skyplot-Fenster zeigt ein Polardiagramm der Satellitenpositionen (Azimut und Elevation) zusammen mit PRN-Nummern und Signal-Rausch-Verhältnis-Balken (SNR) an.

---

## 4. NTRIP-RTK-Differentialkorrektur-Einrichtung

Um zentimetergenaue Positionierung zu erreichen, konfigurieren Sie den integrierten NTRIP-Client:

1. Öffnen Sie das Menü und wählen Sie **Einstellungen** -> **NTRIP Einstellung**.
2. Geben Sie die Zugangsdaten Ihres Korrekturdatendienstes ein:
   * **NTRIP Host:** IP-Adresse oder Hostname des Casters (z. B. `rtk.provider.com`).
   * **NTRIP Port:** Caster-Port (typischerweise `2101` oder `8080`).
   * **NTRIP Mount:** Mountpoint-Kennung (z. B. `VRS_3_2_DE`).
   * **NTRIP User:** Ihr Benutzername.
   * **NTRIP Pass:** Ihr Passwort.
3. **Manuelle Position Option:** Falls keine Live-Position verfügbar ist, aktivieren Sie **Manuelle Position**, um statische GGA-Koordinaten an den Caster zu senden.
4. Tippen Sie auf **Speichern & Start NTRIP**.
5. Nach Verbindungsaufbau werden RTCM3-Korrekturdaten direkt an Ihren Empfänger gestreamt. Beobachten Sie, wie der Status in der oberen Leiste von `SINGLE` -> `FLOAT` -> **`FIX`** wechselt.

---

## 5. Kartenstile & Raster-WMS-Ebenen

### 5.1 Auswahl des Kartenstils
Die Anwendung enthält mehrere vorkonfigurierte Kartenstile:
* **Google Earth Hybrid (Standard):** Hochauflösende Satellitenbilder mit überlagertem Straßennetz und Ortsbezeichnungen.
* **High Contrast (Gelb):** Leuchtend gelber Hintergrund (#FFFF00), optimiert für Ablesbarkeit bei direkter Sonneneinstrahlung und Geometrie-Inspektion.
* Vektorbasierte Standard-Straßenkarten.

### 5.2 WMS (Web Map Service) Konfiguration
1. Öffnen Sie das Menü und tippen Sie auf **WMS Ebenen**.
2. Tippen Sie auf **WMS Ebene hinzufügen**.
3. Geben Sie die WMS-URL (z. B. `https://geodaten.land.de/wms`) und die Ebenennamen (kommagetrennt) ein.
4. Speichern Sie die Konfiguration. WMS-Ebenen werden unter den Vektorgeometrien gerendert und bleiben bis zu Zoomstufe 35,0f sichtbar.

---

## 6. Digitalisierung & Bearbeitung von Geometrien

### 6.1 Punkte erzeugen
* **Manuelles Tippen (`Pkt Zeichnen`):** Tippen Sie auf `Pkt Zeichnen` und anschließend auf den gewünschten Ort auf der Karte. Geben Sie Name und Notizen ein und tippen Sie auf **Speichern**.
* **Punkt an GNSS-Position (`Pkt @ GNSS`):** Tippen Sie auf `Pkt @ GNSS`. Ein Punktobjekt wird sofort an der exakten aktuellen GNSS-Koordinate erstellt.

### 6.2 Linien & Polygone zeichnen
1. Tippen Sie auf `Linie Zeichnen` oder `Poly Zeichnen`.
2. Tippen Sie nacheinander Punkte auf der Karte an, um Eckpunkte zu setzen. Eine temporäre rote Linie bzw. ein Polygon wird in Echtzeit angezeigt.
3. Tippen Sie erneut auf `Linie Zeichnen` bzw. `Poly Zeichnen`, um die Erfassung abzuschließen.
4. Geben Sie Name und Notizen ein und tippen Sie auf **Speichern**. Gespeicherte Objekte werden in Magenta gerendert.

### 6.3 Fangmodus (`Fang`)
* Tippen Sie auf das Magnet-Symbol (`Fang`) in der Zeichenleiste, um den Fangmodus zu aktivieren.
* Beim Setzen oder Verschieben von Punkten springt der Cursor automatisch auf die exakten Koordinaten bestehender Eckpunkte im Umkreis von 10 Metern.

### 6.4 Objekterfassung bearbeiten
1. Tippen Sie auf `Bearbeiten` in der Zeichenleiste.
2. Tippen Sie auf ein Objekt auf der Karte, um den **Eigenschaften-Dialog** zu öffnen.
3. Verfügbare Aktionen:
   * **Speichern:** Aktualisiert Name und Notizen.
   * **Entfernen / Löschen:** Löscht das Objekt dauerhaft.
   * **Verschieben:** Tippen Sie auf `Verschieben` und anschließend auf die neue Position auf der Karte.
   * **Verschieben um...:** Geben Sie eine exakte Entfernung (in Metern) und eine Peilung (0–360°) ein, um das Objekt präzise zu verschieben.

---

## 7. Messwerkzeug & Ziel-Navigation

1. Tippen Sie oben auf `Messen`, um den Messmodus zu aktivieren.
2. Tippen Sie Punkte auf der Karte an, um Strecken zu messen.
3. Tippen Sie auf `Set`, um einen Zielpunkt festzulegen.
4. **Ziel-Pfeil außerhalb des Bildschirms:** Wenn Sie sich vom Zielpunkt entfernen, sodass dieser nicht mehr im Bild sichtbar ist, erscheint am Bildschirmrand ein Navigationspfeil (`ic_nav_arrow`), der in Richtung des Ziels zeigt und die aktuelle Entfernung in Metern anzeigt.

---

## 8. Daten-Import & -Export

### 8.1 GIS-Daten importieren
1. Öffnen Sie das Navigationsmenü und tippen Sie auf **Daten Importieren**.
2. Wählen Sie **CSV Importieren** (Name, Lat, Lon) oder **GIS Datei Importieren** (`.shp`, `.json`, `.qgz`, `.kml`).
3. Wählen Sie die Datei über den System-Dateiauswähler aus. Die Daten werden direkt auf der Karte dargestellt.

### 8.2 GIS-Daten exportieren
1. Öffnen Sie das Navigationsmenü und tippen Sie auf **Daten Exportieren**.
2. Wählen Sie das gewünschte Exportformat:
   * **Shapefile (.zip):** Erzeugt ein vollständiges ZIP-Archiv mit `.shp`-, `.shx`- und `.dbf`-Dateien nach ESRI-Standard.
   * **GeoJSON (.json)**
   * **KML (.kml)**
   * **QGIS Projekt (.qgz)**
3. Speichern Sie die Datei über den Datei-Dialog auf Ihrem Gerät oder der SD-Karte.

---

## 9. Sprach- & Anzeige-Einstellungen

* **Sprachumschaltung:** Öffnen Sie das Menü und tippen Sie auf **Sprache** (DE / EN), um die Benutzeroberfläche zwischen Deutsch und Englisch umzuschalten. Die Einstellung wird dauerhaft gespeichert.
* **Relativer Maßstab:** Zeigt den dynamischen Echtzeit-Kartenmaßstab (z. B. `1:500`) basierend auf der Bildschirmpixeldichte und der Zoomstufe an.
