plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.nekodow.pmpatcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nekodow.pmpatcher"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.maxHeapSize = "1g"
            it.systemProperty("pm.dir", System.getProperty("pm.dir") ?: "")
            it.systemProperty("pm.out", System.getProperty("pm.out") ?: "")
            it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
        }
    }
}

dependencies {
    implementation("org.tukaani:xz:1.9")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
