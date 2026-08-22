# SafeView release rules
# Keep TensorFlow Lite model and interpreter classes discoverable when shrinking is enabled.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# Keep Android service entry points and JNI-facing callbacks.
-keep class com.safeview.app.SafeViewVpnService { *; }
-keep class com.safeview.app.SafeViewScreenAiService { *; }
-keepclassmembers class com.safeview.app.SafeViewBridge {
    <methods>;
}

# Retain model attribution and asset names as packaged resources.
-keepclassmembers class ** { @androidx.annotation.Keep *; }
