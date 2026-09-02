plugins {
    id("com.android.application")
}

android {
    namespace = "com.foresight.gateway"
    compileSdk = 37
    ndkVersion = "27.3.13750724"

    // Keep the NDK machine-local. GW1-A developers set FORESIGHT_ANDROID_NDK to
    // the reproducible NDK r27d installation instead of committing local.properties.
    providers.environmentVariable("FORESIGHT_ANDROID_NDK").orNull?.let { ndkPath = it }

    defaultConfig {
        applicationId = "com.foresight.gateway"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.0")

    testImplementation("junit:junit:4.13.2")
    // Android's platform org.json is a JVM stub in local unit tests; use the same API's
    // reference implementation so the app-private metadata ledger is tested end-to-end.
    testImplementation("org.json:json:20240303")
}
