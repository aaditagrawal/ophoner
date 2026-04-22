# ============================================================================
# kotlinx.serialization
# ============================================================================
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.ophoner.**$$serializer { *; }
-keepclassmembers class dev.ophoner.** { *** Companion; }
-keepclasseswithmembers class dev.ophoner.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep all @Serializable classes and their synthetic $serializer inner classes
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keep,includedescriptorclasses class **$$serializer { *; }

# ============================================================================
# Kotlin / Coroutines
# ============================================================================
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
# Most of volatile fields are updated with AFU and should not be mangled
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-keepclassmembers class kotlinx.coroutines.flow.** { *; }
# Debug metadata for better stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep class kotlin.coroutines.Continuation

# ============================================================================
# Hilt / Dagger / javax.inject
# ============================================================================
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @javax.inject.Singleton class * { *; }
-keepclasseswithmembernames class * {
    @javax.inject.* <fields>;
}
-keepclasseswithmembernames class * {
    @javax.inject.* <init>(...);
}
-keepclasseswithmembernames class * {
    @javax.inject.* <methods>;
}
-keep class * extends dagger.hilt.android.internal.managers.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep class dagger.** { *; }

# ============================================================================
# Room
# ============================================================================
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keep @androidx.room.TypeConverters class * { *; }
-dontwarn androidx.room.paging.**

# ============================================================================
# OkHttp / Okio
# ============================================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
# A resource is loaded with a relative path so the package of this class must be preserved.
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.internal.publicsuffix.** { *; }
-keepclassmembers class okhttp3.** { *; }
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn org.openjsse.**
# Okio
-keep class sun.misc.Unsafe { *; }
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.*

# ============================================================================
# Shizuku
# ============================================================================
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class moe.shizuku.api.** { *; }
-keep interface moe.shizuku.api.** { *; }
-keep class moe.shizuku.server.** { *; }
-keep interface moe.shizuku.server.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# ============================================================================
# AndroidX / Compose (belt-and-suspenders — most handled by consumer rules)
# ============================================================================
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ============================================================================
# AndroidX Security (EncryptedSharedPreferences)
# ============================================================================
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ============================================================================
# Tink / androidx.security — errorprone annotations not on classpath at runtime
# ============================================================================
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.** { *; }

# ============================================================================
# General — keep line numbers for crash reports but obfuscate file names
# ============================================================================
-keepattributes Exceptions, InnerClasses, Signature, Deprecated, SourceFile, LineNumberTable, *Annotation*, EnclosingMethod
