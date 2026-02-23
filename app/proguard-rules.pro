# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mobileclaw.agent.**$$serializer { *; }
-keepclassmembers class com.mobileclaw.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.mobileclaw.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
