// Supervisors' app: a separate APK (dz.association.admin) for EDITOR / ADMIN / SUPER_ADMIN accounts.
// Members never receive any administration code. Every permission is enforced by the server.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// -P on the command line, then the environment (CI variables), then gradle.properties.
fun prop(name: String, default: String = ""): String =
    gradle.startParameter.projectProperties[name] ?: System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(name) as String?) ?: default

android {
    namespace = "dz.association.admin"
    compileSdk = 35

    defaultConfig {
        applicationId = "dz.association.admin"
        minSdk = 24
        targetSdk = 35
        versionCode = prop("VERSION_CODE", "1").toInt()
        versionName = prop("VERSION_NAME", "1.0.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
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
        }
        // Optimised test build (R8, not debuggable) signed with the debug key: installs over the
        // debug build and runs at release speed. Not for distribution.
        create("staging") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-test"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api", "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources { generateLocaleConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint {
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core"))
    // Installs the bundled baseline profiles (Compose etc.) even for APKs installed outside Play: much smoother first runs.
    implementation(libs.profileinstaller)
    implementation(libs.hilt.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.retrofit.serialization)
}
