import java.util.Properties

plugins {
    id("com.android.application")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.nekodow.pmpatcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nekodow.pmpatcher"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.1"
    }

    signingConfigs {
        create("release") {
            // Keystore path and passwords come from local.properties (never committed)
            (localProps["PM_KEYSTORE"] as String?)?.let { storeFile = file(it) }
            storePassword = localProps["PM_KEYSTORE_PASSWORD"] as String? ?: ""
            keyAlias = localProps["PM_KEY_ALIAS"] as String? ?: ""
            keyPassword = localProps["PM_KEY_PASSWORD"] as String? ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (localProps["PM_KEYSTORE"] != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
