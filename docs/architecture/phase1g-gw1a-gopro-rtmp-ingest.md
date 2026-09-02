# Phase 1G-GW1-A: Direct GoPro RTMP Ingest Proof

GW1-A is a narrow Android-only ingress proof. A GoPro HERO8 publishes RTMP to
an explicitly started listener on the phone at `rtmp://PHONE_WIFI_IPV4:1935/gopro`.
The Android foreground service owns the listener rather than an activity, so
activity recreation cannot stop it.

`GoProRtmpIngress` presents independent source states: `STOPPED`, `LISTENING`,
`PUBLISHER_CONNECTED`, `LIVE`, `LOST`, and `ERROR`. These states are diagnostic
only. They do not reuse `StreamLifecycle`, alter FIELD/LAB event enablement, or
change RTSP, local recording, extraction, sync, or source authority.

The JNI bridge owns FFmpeg RTMP listen/open, FLV stream inspection, packet
liveness reads, and interruption through FFmpeg's interrupt callback. It accepts
one publisher at a time, only while explicitly started. It reports H.264/AAC
metadata to Kotlin and discards packets. It does not decode, preview, record,
extract, upload, authenticate, or serve arbitrary clients.

The packaged FFmpeg 9.0.1 static `arm64-v8a` artifacts target Android API 26
under LGPL 2.1+ with GPL and nonfree disabled. Detailed hashes and rebuild
provenance are stored beside the archives in
`platforms/android/foresight-gateway/app/src/main/cpp/third_party/ffmpeg/arm64-v8a/FFMPEG_PROVENANCE.md`.

Authentication and a final multi-source selection experience remain future work.
