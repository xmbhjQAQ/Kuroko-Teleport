import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionPropsFile = rootProject.file("version.properties")
require(versionPropsFile.isFile) {
    "Missing root version.properties at ${versionPropsFile.absolutePath}"
}
val versionProps = Properties().apply {
    versionPropsFile.inputStream().use { load(it) }
}
fun reqVersionProp(key: String): String =
    versionProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("Missing required key '$key' in ${versionPropsFile.name}")

val releaseVersionName = reqVersionProp("RELEASE_VERSION_NAME")
val releaseVersionCode = reqVersionProp("RELEASE_VERSION_CODE").toInt()
val ipcProtocolVersion = reqVersionProp("IPC_PROTOCOL_VERSION").toInt()
val shmSchemaVersion = reqVersionProp("SHM_SCHEMA_VERSION").toInt()
val minCompatModuleCode = reqVersionProp("MIN_COMPAT_MODULE_CODE").toInt()

val buildChannel = (
    project.findProperty("kurokoBuildChannel")?.toString()
        ?: System.getenv("KUROKO_BUILD_CHANNEL")
        ?: "local"
).trim().lowercase()
require(buildChannel in setOf("release", "ci", "local")) {
    "Unsupported KUROKO_BUILD_CHANNEL='$buildChannel' (expected one of: release, ci, local)"
}
val updateCheckEnabled = (buildChannel == "release")
val githubRepo = System.getenv("GITHUB_REPOSITORY")?.trim()?.takeIf { it.isNotEmpty() }
    ?: "xmbhjQAQ/Kuroko-Teleport"

val signingKeystorePath = System.getenv("SIGNING_KEYSTORE_PATH")?.trim().orEmpty()
val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD").orEmpty()
val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")?.trim().orEmpty()
val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD").orEmpty()
val releaseKeystoreFile = signingKeystorePath.takeIf { it.isNotEmpty() }?.let { file(it) }
val hasReleaseSigning = releaseKeystoreFile != null &&
    releaseKeystoreFile.isFile &&
    signingStorePassword.isNotEmpty() &&
    signingKeyAlias.isNotEmpty()

android {
    namespace = "com.kuroko.teleport"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kuroko.teleport"
        minSdk = 31
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        buildConfigField("int", "IPC_PROTOCOL_VERSION", ipcProtocolVersion.toString())
        buildConfigField("int", "SHM_SCHEMA_VERSION", shmSchemaVersion.toString())
        buildConfigField("int", "MIN_COMPAT_MODULE_CODE", minCompatModuleCode.toString())
        buildConfigField("String", "BUILD_CHANNEL", "\"$buildChannel\"")
        buildConfigField("boolean", "UPDATE_CHECK_ENABLED", updateCheckEnabled.toString())
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword.ifEmpty { signingStorePassword }
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")

    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Pure open-source MapLibre Native Android SDK (16KB page-aligned, zero Google Play Services)
    implementation("org.maplibre.gl:android-sdk:11.12.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
