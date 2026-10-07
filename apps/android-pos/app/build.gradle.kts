import java.util.Properties

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun localValue(key: String, fallback: String = ""): String =
    localProperties.getProperty(key, fallback).replace("\\", "\\\\").replace("\"", "\\\"")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.icecreampost.pos"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.icecreampost.pos"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "APP_ENV", "\"development\"")
            buildConfigField("String", "SUPABASE_URL", "\"${localValue("supabase.dev.url")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localValue("supabase.dev.anonKey")}\"")
        }
        create("production") {
            dimension = "environment"
            buildConfigField("String", "APP_ENV", "\"production\"")
            buildConfigField("String", "SUPABASE_URL", "\"${localValue("supabase.production.url")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localValue("supabase.production.anonKey")}\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Never produce a development APK that can modify production data. Unit tests
// remain runnable while the separate development Supabase project is provisioned.
val configuredDevSupabaseUrl = localValue("supabase.dev.url").trim().trimEnd('/').lowercase()
val configuredProductionSupabaseUrl = localValue("supabase.production.url").trim().trimEnd('/').lowercase()
val verifyDevBackendIsolation by tasks.registering {
    group = "verification"
    description = "Ensures development POS builds cannot connect to the production Supabase project."
    doLast {
        if (configuredDevSupabaseUrl.isBlank() || configuredProductionSupabaseUrl.isBlank()) {
            throw GradleException("Configure supabase.dev.url and supabase.production.url in local.properties before building a dev APK.")
        }
        if (configuredDevSupabaseUrl == configuredProductionSupabaseUrl) {
            throw GradleException("Dev and production currently use the same Supabase project. Create a separate development project and set supabase.dev.url before building or installing a dev APK.")
        }
    }
}
tasks.configureEach {
    if (name.startsWith("assembleDev") || name.startsWith("bundleDev") || name.startsWith("installDev")) {
        dependsOn(verifyDevBackendIsolation)
    }
}

// OneDrive may turn generated files into reparse points and prevent KSP from
// snapshotting or deleting them. Keep generated build output local to Windows
// Temp when this source checkout lives inside OneDrive.
if (projectDir.absolutePath.contains("OneDrive", ignoreCase = true)) {
    layout.buildDirectory.set(file("${System.getProperty("java.io.tmpdir")}coolerz-pos-build"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
