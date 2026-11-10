plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.a_m_protasov.a747"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.a_m_protasov.a747"
        minSdk = 26
        targetSdk = 37
        // -PversionName=1.2.3 (CI passes the tag); versionCode is derived from it
        val v = (project.findProperty("versionName") as String?) ?: "0.1.0"
        versionName = v
        versionCode = v.split(".").map { it.toIntOrNull() ?: 0 }.let { (it + listOf(0, 0, 0)).take(3) }
            .let { (a, b, c) -> a * 10000 + b * 100 + c }.coerceAtLeast(1)
    }

    signingConfigs {
        create("release") {
            // CI provides these; local release builds fall back to the debug key
            val ks = System.getenv("A747_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("A747_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("A747_KEY_ALIAS")
                keyPassword = System.getenv("A747_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("A747_KEYSTORE") != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    sourceSets {
        getByName("main") {
            // map page, MapLibre and time zones are shared with the macOS app
            assets.srcDirs("../../shared/map", "src/main/sharedAssets")
        }
    }

    androidResources {
        generateLocaleConfig = true
        // only the app's own languages (drops AndroidX translations for other locales)
        localeFilters += listOf("en", "ru", "sr", "uk", "kk", "ka", "es", "pt-rBR", "fr", "de", "it", "pl", "tr",
            "zh-rCN", "ja", "ko", "ar", "hi", "in")
    }
}

// timezones.bin is copied next to the map assets so both come from shared/
val copyTimeZones by tasks.registering(Copy::class) {
    from("../../shared/timezones.bin")
    into("src/main/sharedAssets")
}
tasks.named("preBuild") { dependsOn(copyTimeZones) }

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
}
