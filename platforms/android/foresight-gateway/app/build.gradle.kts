plugins {
    id("com.android.application")
}

android {
    namespace = "com.foresight.gateway"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.foresight.gateway"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
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
