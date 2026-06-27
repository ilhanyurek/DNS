# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.ilhanyurek.privatednstiles.** {
    kotlinx.serialization.KSerializer serializer(...);
}
