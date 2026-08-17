/**
 * AGP 9 compiles Kotlin itself and carries its own Kotlin Gradle Plugin — 2.2.10 for 9.0.x. This
 * pin is what keeps the project on the version the catalog names instead: the Compose compiler
 * plugin is versioned in lockstep with the Kotlin compiler, so a catalog that says 2.2.20 and a
 * compiler that is 2.2.10 is a mismatch the build reports and stops on.
 *
 * `strictly` is deliberate — a plain version here would lose to AGP's own richer constraint.
 */
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin") {
            version { strictly(libs.versions.kotlin.get()) }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
