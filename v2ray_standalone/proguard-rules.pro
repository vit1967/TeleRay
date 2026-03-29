# V2RayNG ProGuard Rules

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep V2Ray core classes
-keep class com.v2ray.ang.** { *; }
-keep class libv2ray.** { *; }
-dontwarn libv2ray.V2RayPoint

# Keep MMKV
-keep class com.tencent.mmkv.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep data classes
-keepclassmembers class com.v2ray.ang.dto.** { *; }

# Keep IPC classes
-keep class com.v2ray.ang.receiver.V2RayNGReceiver { *; }
-keep class com.v2ray.ang.helper.ProfileImporter { *; }
