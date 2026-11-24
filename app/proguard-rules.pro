# ProGuard / R8 Rules for AmapWatermarkCamera v9.0.0

-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses

# Keep Android components & FileProvider
-keep public class com.fast.amapwatermark.MainActivity
-keep class androidx.core.content.FileProvider { *; }

# Keep data models & serialization classes
-keep class com.fast.amapwatermark.util.WatermarkConfig { *; }
-keep class com.fast.amapwatermark.util.WatermarkConfig$* { *; }
-keep class com.fast.amapwatermark.util.LocationSnapshot { *; }
-keep class com.fast.amapwatermark.util.PhotoEntry { *; }
-keep class com.fast.amapwatermark.util.SystemCameraPhoto { *; }
-keep class com.fast.amapwatermark.util.LatLon { *; }
-keep class com.fast.amapwatermark.util.QrCodeGenerator$VerifiedLocationPayload { *; }

# Keep CameraX & ExifInterface internals
-keep class androidx.camera.** { *; }
-keep class androidx.exifinterface.media.ExifInterface { *; }

# Kotlin coroutines & metadata safety
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
-dontwarn com.google.common.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
