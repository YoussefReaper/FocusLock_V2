import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing. Kept in a gitignored file that points at a keystore stored
// outside the repo, so neither the key nor the passwords are ever committed.
// If the file is absent the release build still succeeds, signed with the debug
// key — fine for a local smoke test, not shippable.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")
    ?.let { file(it).exists() } == true

android {
    namespace = "com.focuslock.mdm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.focuslock.mdm"
        minSdk = 29
        targetSdk = 36
        // Bump both on every build that reaches a real phone. People are running
        // 1.0 already; without this, two different APKs both report "1.0" and the
        // only way to tell them apart is a checksum.
        versionCode = 17
        versionName = "1.16"

        buildConfigField("boolean", "ENFORCEMENT", "true")
        buildConfigField("boolean", "DEMO", "false")
        buildConfigField("String", "TIER", "\"full\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true   // sideloading onto older images
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            // Shrinks unused code and resources (most of it Media3's) but
            // does not rename anything - see proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Optional: turns on content matching for Earn Mode photo proof.
    //
    // PhotoProof works without it — it catches reused photos, blank frames and
    // stale files with no model at all, which are the cheats people actually
    // use. Adding this line and implementing PhotoProof.ContentMatcher against
    // ImageLabeling adds a 400-label check of what is in the frame. The model is
    // bundled, runs fully offline, and adds roughly 5-10 MB to the APK.
    //
    // implementation("com.google.mlkit:image-labeling:17.0.9")

    implementation("androidx.documentfile:documentfile:1.1.0")
    // Declared explicitly rather than inherited. AppBlockerService runs the
    // enforcement loop on coroutines; it compiled for a long time only because
    // some AndroidX library happened to pull them in transitively, which means
    // a routine version bump in any of the four dependencies above could have
    // broken the build with an error pointing at our own file.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    // The real org.json for unit tests; Android's own is a stub off-device.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
