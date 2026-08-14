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

/**
 * Release signing. The keystore lives outside the repo and its passwords in a git-ignored
 * keystore.properties beside it, with an environment fallback so CI can inject them instead.
 *
 * Absent on a machine that has neither, the release build stays unsigned rather than failing
 * to configure — a fresh clone can still run every debug task and the unit tests.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signing(key: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv("WHAAACK_${key.uppercase()}"))
        ?.takeIf { it.isNotBlank() }

/**
 * Present-but-wrong must not silently degrade to an unsigned build. Skipping the config when
 * `keystore.properties` is absent is intentional — a fresh clone and a CI runner without
 * secrets still configure and can run every debug task. But once someone has declared a
 * keystore, a path that does not resolve is a mistake, and the failure mode is nasty: the
 * build stays green and hands back an artifact Play rejects. (This is not hypothetical — the
 * first attempt wrote the path in Git Bash form, `/c/Users/...`, which the JVM on Windows
 * cannot open, and produced exactly that.)
 */
val releaseStore: File? = signing("storeFile")?.let(::File)?.also {
    check(it.exists()) {
        "keystore.properties points at a keystore that does not exist: ${it.absolutePath} — " +
            "on Windows use a path the JVM can open (C:/Users/...), not a Git Bash path (/c/Users/...)."
    }
}

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
            "ADMOB_INTERSTITIAL_AD_UNIT_ID",
            "\"${secret("ADMOB_INTERSTITIAL_AD_UNIT_ID", "ca-app-pub-6904561240517963/2703686934")}\"",
        )
        // Supabase hands out a Google *Web* client id to verify ID tokens against.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${secret("GOOGLE_WEB_CLIENT_ID")}\"",
        )

        // The one-time "remove ads" product, as created in the Play Console. The id below is
        // a placeholder: the product does not exist yet, so Play answers the lookup with
        // ITEM_UNAVAILABLE and BillingManager reports the store as simply having nothing to
        // sell. The button hides itself in that state, so shipping before the product is
        // created is safe — set the real id here (or in local.properties) when it exists.
        buildConfigField(
            "String",
            "REMOVE_ADS_PRODUCT_ID",
            "\"${secret("REMOVE_ADS_PRODUCT_ID", "whaaack_remove_ads")}\"",
        )
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = signing("storePassword")
                keyAlias = signing("keyAlias")
                keyPassword = signing("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Google's reserved always-fill test unit, so debug runs never touch live
            // inventory. This is the *Interstitial* test id; the format has to match the
            // one the code loads or it never fills.
            buildConfigField(
                "String",
                "ADMOB_INTERSTITIAL_AD_UNIT_ID",
                "\"ca-app-pub-3940256099942544/1033173712\"",
            )
        }
        release {
            signingConfig = signingConfigs.findByName("release")
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

    implementation(libs.billing.ktx)

    // GameEngine is deliberately free of Android types, so its rules are testable on the JVM.
    testImplementation(libs.junit)
}
