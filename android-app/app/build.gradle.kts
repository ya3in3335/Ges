plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/** Firebase registers each package name separately; the ".debug" builds use their own app id. */
fun firebaseDebugAppId(): String? = prop("FIREBASE_APP_ID_DEBUG").takeIf { it.isNotBlank() }

// -P on the command line, then the environment (CI variables), then gradle.properties.
fun prop(name: String, default: String = ""): String =
    gradle.startParameter.projectProperties[name] ?: System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(name) as String?) ?: default

android {
    namespace = "dz.association.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dz.association.app"
        minSdk = 24
        targetSdk = 35
        versionCode = prop("VERSION_CODE", "1").toInt()
        versionName = prop("VERSION_NAME", "1.0.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val apiBase = prop("API_BASE_URL", "https://api.example.invalid/")
        require(apiBase.startsWith("https://") && apiBase.endsWith("/")) { "API_BASE_URL must be https and end with /" }
        buildConfigField("String", "API_BASE_URL", "\"$apiBase\"")
        // Firebase is initialised manually from these (public) client identifiers; push is disabled when empty.
        buildConfigField("String", "FIREBASE_APP_ID", "\"${prop("FIREBASE_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${prop("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${prop("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${prop("FIREBASE_SENDER_ID")}\"")
    }

    signingConfigs {
        // Release signing comes only from environment / CI secrets. Keystores are never committed.
        val ks = prop("ANDROID_KEYSTORE_FILE")
        if (ks.isNotEmpty() && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = prop("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = prop("ANDROID_KEY_ALIAS")
                keyPassword = prop("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            firebaseDebugAppId()?.let { buildConfigField("String", "FIREBASE_APP_ID", "\"$it\"") }
        }
        // Optimised test build (R8, not debuggable) signed with the debug key: installs over the
        // debug build and runs at release speed. Not for distribution.
        create("staging") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-test"
            matchingFallbacks += listOf("release")
            firebaseDebugAppId()?.let { buildConfigField("String", "FIREBASE_APP_ID", "\"$it\"") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
    // Installs the bundled baseline profiles (Compose etc.) even for APKs installed outside Play: much smoother first runs.
    implementation(libs.profileinstaller)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization)
    implementation(libs.coil.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.play.services)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
