# Release logging is deliberately NOT stripped.
#
# Eleven android.util.Log call sites ship: ten Log.w and one Log.i, in AdsManager,
# ConsentManager, AudioEngine, BillingManager and MainActivity. Each was checked — none emits
# a token, an email or the Supabase key; the worst is Play Billing's own debugMessage and
# GMA/UMP error text. Against that, the app has no crash reporter, so a logcat capture
# attached to a bug report is the only diagnostic signal that exists for a failed purchase,
# a refused consent form or a dead MediaPlayer.
#
# Note the obvious rule would not have worked anyway: -assumenosideeffects on v/d/i removes
# exactly one of the eleven, since there is no Log.v, Log.d or Log.e anywhere in the app.
# Silencing the rest means listing `w`, which throws away precisely the lines worth keeping.

# kotlinx.serialization keeps its generated serializers on the companion; R8 needs the hint.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class tech.idct.whaaack.** {
    *** Companion;
}
-keepclasseswithmembers class tech.idct.whaaack.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class tech.idct.whaaack.**$$serializer { *; }

# Room instantiates its generated `*_Impl` database by reflection, and the rule it needs is not the
# one it ships. room-runtime 2.2.5 — pulled in transitively by androidx.work 2.7.0, which arrives
# under Play Billing / Play services; nothing in this app uses either directly — carries only
# `-keep class * extends androidx.room.RoomDatabase`, with no member specification. AGP 9 turns on
# `android.r8.strictFullModeForKeepRules`, under which that keeps the class and *not* its
# constructor, so `newInstance()` fails and WorkManager's initializer takes the process down at
# startup:
#
#   Unable to get provider androidx.startup.InitializationProvider
#     Caused by: Failed to create an instance of androidx.work.impl.WorkDatabase
#
# Release-only, launch-immediate, and invisible to every debug build — this cost a release-build
# smoke test to find. The rule below is the one Room itself ships from 2.3 onwards.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}

# OkHttp ships optional Conscrypt/BouncyCastle hooks that are absent at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
