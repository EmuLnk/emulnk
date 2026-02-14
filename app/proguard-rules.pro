# EmuLink ProGuard Rules for Release Builds
# Note: minifyEnabled is currently false in build.gradle, but these rules
# prepare the codebase for future code shrinking and obfuscation.

# ============================
# Debugging & Stack Traces
# ============================
# Preserve source file names and line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*

# ============================
# JavaScript Bridge
# ============================
# Preserve @JavascriptInterface annotated methods for WebView theme communication
-keepclassmembers class com.emulnk.bridge.EmuLinkBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the entire bridge class to ensure proper initialization
-keep class com.emulnk.bridge.EmuLinkBridge { *; }

# ============================
# Gson / JSON Serialization
# ============================
# Keep model classes used for JSON parsing (consoles.json, profiles/*.json, themes/*/theme.json)
-keep class com.emulnk.model.** { *; }

# Generic Gson rules
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Prevent stripping generic type information for Gson reflection
-keepattributes Signature

# ============================
# OkHttp (Network)
# ============================
# OkHttp platform used for HTTP requests in SyncService
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.platform.** { *; }
-keep class okhttp3.internal.connection.** { *; }

# ============================
# Kotlin
# ============================
# Preserve Kotlin metadata for reflection
-keep class kotlin.Metadata { *; }

# Keep Kotlin coroutines (used throughout viewModel and services)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ============================
# BuildConfig
# ============================
# Preserve BuildConfig for runtime debug checks
-keep class com.emulnk.BuildConfig { *; }

# ============================
# Android Components
# ============================
# Keep custom views and their constructors
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# AndroidX and Compose: consumer rules from libraries handle this automatically

# ============================
# Enum Classes
# ============================
# Prevent enum value name obfuscation (used in config parsing)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}