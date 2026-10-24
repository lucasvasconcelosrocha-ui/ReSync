# ReSync Proguard Rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.resync.app.data.model.** { *; }
-keep class com.resync.app.** { *; }

# WorkManager Workers
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class androidx.work.WorkerParameters { *; }

# Room Database
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# LibSU (Root operations)
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Apache Commons Compress, zstd-jni, lz4-java
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**
-keep class net.jpountz.** { *; }
-dontwarn net.jpountz.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coroutines
-dontwarn kotlinx.coroutines.**

# Keep enum values for reflection and serialization
-keepclassmembers enum * { *; }

# Google API Client, Play Services & Drive API
-keep class com.google.api.client.** { *; }
-keep interface com.google.api.client.** { *; }
-dontwarn com.google.api.client.**
-keep class com.google.api.services.drive.** { *; }
-keep interface com.google.api.services.drive.** { *; }
-dontwarn com.google.api.services.drive.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn com.google.errorprone.annotations.**
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}

# Java SE classes not present in Android (referenced by Google HTTP Client / Apache)
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**
-dontwarn java.awt.**



