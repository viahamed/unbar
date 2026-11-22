# Keep gomobile Go core classes and JNI bindings
-keep class core.** { *; }
-keep class go.** { *; }
-dontwarn go.**
-dontwarn core.**

# Keep kotlinx.serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.breakthebarriers.unbar.data.config.**$$serializer { *; }
-keepclassmembers class com.breakthebarriers.unbar.data.config.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.breakthebarriers.unbar.data.tunnel.**$$serializer { *; }
-keepclassmembers class com.breakthebarriers.unbar.data.tunnel.** {
    *** Companion;
}

# Keep Room entities/DAOs
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
