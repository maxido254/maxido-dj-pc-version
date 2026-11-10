# Shrink and optimise, but keep names (JNI, ONNX Runtime and the crash log stay readable).
-dontobfuscate
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.example.djapp.NativeEngine { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-keep class * extends android.app.Service
-dontwarn javax.annotation.**
