# Piko keeps model API keys out of logs and uses Android Keystore at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.piko.agent.**$$serializer { *; }
-keepclassmembers class com.piko.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.piko.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
