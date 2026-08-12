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
