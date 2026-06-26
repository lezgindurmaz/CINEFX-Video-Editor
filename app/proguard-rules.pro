# CINEFX ProGuard Rules

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Keep Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Compose
-dontwarn androidx.compose.**

# Keep the Application class
-keep class com.cinefx.videoeditor.CineFxApp { *; }

# Keep data classes used by the app
-keep class com.cinefx.videoeditor.ExportConfig { *; }
-keep class com.cinefx.videoeditor.VideoTextOverlay { *; }
-keep class com.cinefx.videoeditor.SubtitleItem { *; }
-keep class com.cinefx.videoeditor.EffectItem { *; }
-keep class com.cinefx.videoeditor.FilterItem { *; }

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
