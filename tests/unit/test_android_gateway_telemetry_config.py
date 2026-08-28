from pathlib import Path


def test_android_gateway_explicitly_permits_its_local_http_telemetry_transport() -> None:
    manifest = Path("platforms/android/foresight-gateway/app/src/main/AndroidManifest.xml")

    text = manifest.read_text(encoding="utf-8")

    assert 'android:usesCleartextTraffic="true"' in text
    assert 'android.permission.INTERNET' in text
