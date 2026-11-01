# A7 LiveView

Android-App, die das Live-View-Bild einer Sony A7 II per WLAN anzeigt, mit Focus Peaking, Zebra und Histogramm.

## Voraussetzungen an der Kamera

1. Auf der A7 II muss die In-Camera-App **Smart Remote Control** installiert sein (PlayMemories Camera Apps).
2. Kamera: Menü → Anwendung → **Smart Remote Control** starten. Die Kamera zeigt SSID und Passwort an.
3. Handy mit diesem WLAN verbinden (es hat kein Internet, das ist normal).

## Bauen

1. Ordner in Android Studio öffnen (Gradle-Sync lädt Gradle 8.7 und das Android-Gradle-Plugin).
2. Handy per USB anschließen, USB-Debugging aktivieren, Run.

## Bedienung

- **Verbinden:** sucht die Kamera per SSDP (Rückfall: 192.168.122.1:8080) und startet den Stream.
- **Peaking:** grüne Kanten, wo das Bild scharf ist.
- **Zebra:** rote Streifen ab Luma 235 von 255 (`FrameAnalyzer.zebraLevel`).
- **Histogramm:** Luma-Histogramm oben rechts.

Schwellen für Peaking und Zebra stehen in `FrameAnalyzer.kt`.

## Bekannte Einschränkungen

- Das Live-View-Bild per WLAN ist klein (ca. 640 px) und leicht verzögert.
- Im Smart-Remote-Modus sind bei Sony-Kameras meist Picture Profiles (S-Log) deaktiviert,
  das Bild zeigt also keinen Log-Look.
- Peaking und Zebra beruhen auf dem JPEG-Vorschaubild, nicht auf dem Sensor-RAW.
