import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Secrets live in local.properties (git-ignored) so nothing sensitive lands in the repo.
 * Falls back to environment variables so CI can inject them instead.
 */
val secrets = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String, default: String = ""): String =
    secrets.getProperty(key) ?: System.getenv(key) ?: default

android {
    namespace = "tech.idct.whaaack"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.idct.whaaack"
        // 26 is where SurfaceHolder.lockHardwareCanvas() arrived, which is how the render
        // thread gets a GPU-backed Canvas. See GameSurfaceView.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // The AdMob application id is not a secret — it ships in every APK's manifest.
        manifestPlaceholders["admobAppId"] = secret(
            "ADMOB_APP_ID",
            "ca-app-pub-6904561240517963~2412756903",
        )

        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
        buildConfigField(
            "String",
            "ADMOB_REWARDED_AD_UNIT_ID",
            "\"${secret("ADMOB_REWARDED_AD_UNIT_ID", "ca-app-pub-6904561240517963/7453330598")}\"",
        )
        // Supabase hands out a Google *Web* client id to verify ID tokens against.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${secret("GOOGLE_WEB_CLIENT_ID")}\"",
        )
    }

    buildTypes {
        debug {
            // Google's reserved always-fill test unit, so debug runs never touch live inventory.
            buildConfigField(
                "String",
                "ADMOB_REWARDED_AD_UNIT_ID",
                "\"ca-app-pub-3940256099942544/5354046379\"",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Sprites and audio are streamed straight from assets/; keep them uncompressed so
        // the render thread can mmap-decode without an inflate step mid-frame.
        noCompress += listOf("png", "ogg", "wav")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
}
