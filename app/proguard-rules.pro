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

# OkHttp ships optional Conscrypt/BouncyCastle hooks that are absent at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
