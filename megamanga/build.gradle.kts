plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.omnistream.megamanga"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omnistream.megamanga"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("org.koitharu:kotatsu-parsers:1.0") {
        exclude(group = "org.json", module = "json")
        exclude(group = "org.jsoup")
        exclude(group = "com.squareup.okhttp3")
        exclude(group = "com.squareup.okio")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "androidx.collection")
    }

    compileOnly("com.omnistream:plugin-api:1.0")
    compileOnly("com.omnistream:plugin-api-android:1.0")
    compileOnly("org.jsoup:jsoup:1.21.2")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    compileOnly("androidx.collection:collection:1.4.5")
}

tasks.register("packagePlugin") {
    dependsOn("assembleRelease")
    doLast {
        val apk = layout.buildDirectory
            .file("outputs/apk/release/${project.name}-release-unsigned.apk").get().asFile
        val dist = rootProject.layout.projectDirectory.dir("dist").asFile
        dist.mkdirs()
        val omni = File(dist, "megamanga.omni")
        apk.copyTo(omni, overwrite = true)
        println("Packaged: ${omni.absolutePath} (${omni.length() / 1024} KB)")
    }
}
