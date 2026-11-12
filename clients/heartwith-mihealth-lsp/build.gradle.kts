import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val heartwithVersionCode = (findProperty("heartwithClientVersionCode") as String).toInt()
val heartwithVersionName = findProperty("heartwithClientVersionName") as String

val releaseSigning: Map<String, String>? = run {
    val local = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) localFile.inputStream().use { local.load(it) }
    fun value(key: String, env: String): String? =
        (local.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
    val values = mapOf(
        "storeFile" to value("heartwith.release.storeFile", "HEARTWITH_RELEASE_STORE_FILE"),
        "storePassword" to value("heartwith.release.storePassword", "HEARTWITH_RELEASE_STORE_PASSWORD"),
        "keyAlias" to value("heartwith.release.keyAlias", "HEARTWITH_RELEASE_KEY_ALIAS"),
        "keyPassword" to value("heartwith.release.keyPassword", "HEARTWITH_RELEASE_KEY_PASSWORD"),
    )
    if (values.values.all { it != null }) values.mapValues { it.value!! } else null
}

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
        // Dedicated release key, read from local.properties or the environment so no secret is
        // committed. Without it, release keeps the debug key so existing installs still update.
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName(if (releaseSigning != null) "release" else "debug")
            // R8 runs as an optimizer only. Rules keep Xposed/NPatch entry points and names intact
            // while removing debug-only call sites and their eager string construction.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation("com.heartwith:heartwith-android-uploader")
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly(project(":xposed-api-stub"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.github.libxposed:api:102.0.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
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
