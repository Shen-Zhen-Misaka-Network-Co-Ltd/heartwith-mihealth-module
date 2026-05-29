plugins {
    alias(libs.plugins.android.application)
}

val heartwithVersionCode = (findProperty("heartwithClientVersionCode") as String).toInt()
val heartwithVersionName = findProperty("heartwithClientVersionName") as String

android {
    namespace = "com.heartwith.mihealth.lsp"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.heartwith.mihealth.lsp"
        minSdk = 23
        targetSdk = 36
        versionCode = heartwithVersionCode
        versionName = heartwithVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
            // Xposed/NPatch discovers entry points and hooks through reflection.
            // Keep release bytecode unminified; verbose logs are still gated by BuildConfig.DEBUG.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.0")
    compileOnly(project(":xposed-api-stub"))
}

afterEvaluate {
    tasks.named("assembleRelease") {
        doLast {
            val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val source = releaseDir.resolve("heartwith-mihealth-lsp-release.apk")
            val target = releaseDir.resolve("Heartwith-MiHealth-LSP-v$heartwithVersionName-$heartwithVersionCode-release.apk")
            if (source.exists()) source.copyTo(target, overwrite = true)
        }
    }
}
