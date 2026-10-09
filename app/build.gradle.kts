import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("kapt")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists() &&
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { !keystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.lumisignal.idxscreener"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.lumisignal.idxscreener"
        minSdk = 28
        targetSdk = 35
        // Deliberately monotonic and well above the early test builds so Android
        // cannot silently keep an older APK when installing this release.
        versionCode = 11205
        versionName = "1.12.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

configurations.configureEach {
    // Vendored Kotlin serialization runtime avoids a fragile network-only
    // transitive fetch and is binary compatible with Navigation's 1.6.x API.
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core-jvm")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation(files("../gradle/offline-libs/kotlinx-serialization-core-jvm-1.6.2.jar"))

    // Vendored test-only artifacts keep verification reproducible when Maven
    // Central is temporarily unreachable. Android itself supplies org.json at runtime.
    testImplementation(files("../gradle/offline-libs/junit-4.13.2.jar"))
    testImplementation(files("../gradle/offline-libs/hamcrest-core-1.3.jar"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
