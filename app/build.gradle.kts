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
    // Read through a UTF-8 Reader, not the raw stream: `Properties.load(InputStream)` is
    // specified as ISO-8859-1, so any non-ASCII value arrives mojibake — which a localised
    // placeholder price (`12,99 zł`) demonstrates immediately. Every key here is ASCII today
    // and ASCII is a subset of UTF-8, so nothing else changes.
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
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

        // The one-time "remove ads" product — "Whaaack the ads!" — as it exists in the Play
        // Console: a one-time product `no.ads.forever` with a single active, *backwards
        // compatible* purchase option (`no-ads-forever-buy`). Backwards compatible is the
        // part that matters to the code: it is what keeps the legacy singular offer visible
        // through `oneTimePurchaseOfferDetails`, which is where BillingManager reads the
        // price and how `launchPurchase` can pass no offer token.
        //
        // Ids are permanent in the console and cannot be reused after deletion, so this
        // string is not a thing to tidy up later. An id that does not resolve is not a crash:
        // Play answers ITEM_UNAVAILABLE, BillingManager reports the store as having nothing
        // to sell, and every upsell hides itself.
        buildConfigField(
            "String",
            "REMOVE_ADS_PRODUCT_ID",
            "\"${secret("REMOVE_ADS_PRODUCT_ID", "no.ads.forever")}\"",
        )

        // Empty in release, always — the debug build type is the only place this is filled in.
        buildConfigField("String", "REMOVE_ADS_PLACEHOLDER_PRICE", "\"\"")
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

            // Play Billing will not price a product for a build it did not install: a
            // sideloaded debug APK gets SERVICE_DISCONNECTED or ITEM_UNAVAILABLE, so
            // `removeAdsPrice` stays null, and every upsell — the Home row, the Settings row
            // and the ad-break dialog — correctly hides itself. That is right in production
            // and useless while building the screens, so a debug build may stand a price in.
            //
            // Set REMOVE_ADS_PLACEHOLDER_PRICE in local.properties (git-ignored) to any
            // display string, e.g. `REMOVE_ADS_PLACEHOLDER_PRICE=12,99 zł`. Blank by default,
            // so a plain debug build behaves exactly like a release one. The purchase itself
            // is *not* faked: tapping through reaches Play, which declines, which is the
            // honest half of the flow and the half worth seeing fail.
            buildConfigField(
                "String",
                "REMOVE_ADS_PLACEHOLDER_PRICE",
                "\"${secret("REMOVE_ADS_PLACEHOLDER_PRICE", "")}\"",
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
    testImplementation(libs.mockwebserver)
}
