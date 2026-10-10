plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.peakform.fitness"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.peakform.fitness"
        minSdk = 26
        targetSdk = 34
        versionCode = 18
        versionName = "1.4.8"
    }

    signingConfigs {
        create("release") {
            // Deterministic uber-apk-signer embedded debug keystore —
            // cert SHA-256 1e08a903aef9c3a721510b64ec764d01d3d094eb954161b62544ea8f187b5953
            // (unchanged across all batches so adb install -r upgrades in place).
            // BATCH-2A cleanup: the keystore no longer lives in the source tree.
            // Resolution order: -Ppro.keystore=<path> → $PRO_KEYSTORE →
            // <repo-root>/keystore/debug.keystore (next to source/, not inside it) →
            // ~/.keys/debug.keystore.
            val keystoreCandidates = listOf(
                providers.gradleProperty("pro.keystore").orNull,
                System.getenv("PRO_KEYSTORE"),
                File(rootDir.parentFile, "keystore/debug.keystore").absolutePath,
                File(System.getProperty("user.home"), "keys/debug.keystore").absolutePath,
            ).filterNotNull().filter { File(it).isFile() }
            if (keystoreCandidates.isEmpty()) {
                throw GradleException(
                    "Release keystore not found. Place debug.keystore (cert 1e08a903…) at " +
                    "${File(rootDir.parentFile, "keystore/debug.keystore").absolutePath} or ~/.keys/debug.keystore, " +
                    "or pass -Ppro.keystore=<path> / set PRO_KEYSTORE."
                )
            }
            storeFile = file(keystoreCandidates.first())
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "ttf"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("roborazzi.enabled", "true")
            test.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
}
