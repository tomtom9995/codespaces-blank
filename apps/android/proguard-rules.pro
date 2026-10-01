# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.example.nova.shared.api.** { *; }
# Ktor
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
