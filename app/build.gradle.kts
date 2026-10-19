import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from environment variables (set by GitHub Actions).
// Local builds without a keystore fall back to the debug key; on CI a missing key fails the build
// so a debug-signed APK is never published as a release.
val releaseKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() && file(it).exists() }
val isCi: Boolean = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
val releaseSigningComplete: Boolean = releaseKeystorePath != null &&
    listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all { !System.getenv(it).isNullOrBlank() }

// Opt-in usage stats go to VKNXWorks' self-hosted Aptabase server. The app key comes from the APTABASE_KEY
// environment variable (a GitHub Actions secret) or `aptabase.key` in local.properties. Without one, the
// build can't send stats at all and the setting is hidden, so builds from source never send anything.
val aptabaseKey: String = System.getenv("APTABASE_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { stream -> load(stream) } }.getProperty("aptabase.key")?.trim()
    }
    ?: ""
val aptabaseHost = "https://telemetry.vkhub.org"

android {
    namespace = "com.vknxworks.proxctrl"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vknxworks.proxctrl"
        minSdk = 33
        targetSdk = 36
        // Bump both to publish a new release (see CHANGELOG.md).
        versionCode = 13
        versionName = "0.7.5"

        buildConfigField("String", "APTABASE_KEY", "\"${aptabaseKey.replace("\"", "")}\"")
        buildConfigField("String", "APTABASE_HOST", "\"$aptabaseHost\"")
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                releaseSigningComplete -> signingConfigs.getByName("release")
                isCi -> throw GradleException(
                    "Release signing is not configured on CI (keystore, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD). " +
                        "Refusing to sign the release with the debug key.",
                )
                else -> signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // F-Droid: no Google dependency-info block in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.ui.text.ExperimentalTextApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    // Serves the bundled console pages (noVNC, xterm.js) from a secure https origin.
    implementation(libs.androidx.webkit)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.haze)
    // UnifiedPush (ntfy and other distributors) for push notifications without Google services.
    implementation(libs.unifiedpush.connector)
}
