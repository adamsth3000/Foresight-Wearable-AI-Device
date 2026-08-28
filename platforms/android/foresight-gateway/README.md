# Foresight Android Gateway

This is the Phase 1A native Android gateway for a Galaxy S24 FE. It publishes
rear-camera H.264 and microphone AAC to a local MediaMTX RTSP endpoint while a
user-started foreground service remains active. It does not contain GPS,
sensors, rolling media storage, event extraction, cloud upload, or any Python
capture-pipeline code.

## Prerequisites

- Android Studio with Android SDK Platform 35 and Build-Tools 35 installed
- JDK 17
- Android Platform Tools (`adb`)
- A local MediaMTX instance on the Windows laptop accepting RTSP on port 8554

The project pins RootEncoder `2.8.0` from JitPack. RootEncoder is isolated in
`RtspPublisher`; it is not a Foresight core contract.

## Build And Install

From this directory, after installing Gradle 8.10.2 or generating a Gradle
wrapper with Android Studio:

```powershell
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## MediaMTX Test Endpoint

Set the laptop RTSP listener to its local Wi-Fi or hotspot address and start
MediaMTX. For example:

```yaml
rtsp: true
rtspTransports: [tcp]
rtspAddress: 192.168.1.10:8554

paths:
  foresight-phone:
```

Enter the matching endpoint in the app:

```text
rtsp://192.168.1.10:8554/foresight-phone
```

The app must be visible when the user starts capture. Android does not permit a
camera or microphone foreground service to be created from the background.
Use the persistent notification's `Stop capture` action to end the stream.
