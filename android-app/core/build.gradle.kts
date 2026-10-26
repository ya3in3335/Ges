// Code shared by the members' app (:app) and the supervisors' app (:admin):
// networking, secure session, offline cache, preferences, theme, UI components and strings.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun prop(name: String, default: String = ""): String =
    (project.findProperty(name) as String?) ?: System.getenv(name) ?: default

android {
    namespace = "dz.association.app.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        val apiBase = prop("API_BASE_URL", "https://api.example.invalid/")
        require(apiBase.startsWith("https://") && apiBase.endsWith("/")) { "API_BASE_URL must be https and end with /" }
        buildConfigField("String", "API_BASE_URL", "\"$apiBase\"")
        consumerProguardFiles("consumer-rules.pro")
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
    lint {
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    api(libs.androidx.lifecycle.runtime)
    api(libs.androidx.lifecycle.viewmodel)
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.material3)
    api(libs.compose.material.icons)
    api(libs.coil.compose)
    api(libs.retrofit)
    api(libs.retrofit.serialization)
    api(libs.okhttp)
    implementation(libs.okhttp.doh)
    implementation(libs.okhttp.logging)
    api(libs.kotlinx.serialization)
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    api(libs.datastore.preferences)
    api(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
