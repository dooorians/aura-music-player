# Add project specific ProGuard rules here.
-keep class com.ikko.aura.data.model.** { *; }

# ONNX Runtime's native side looks its own Java classes and methods up by name through
# JNI GetMethodID, so renaming any of them aborts the VM the first time a model runs
# (observed as a JniAbort inside convertToTensorInfo). Its packaged consumer rules do not
# cover the paths Automix exercises.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# The analysis store's records are serialised by name into the on-disk cache, so they have
# to survive minification with their field names intact.
-keep class com.ikko.aura.player.smart.** { *; }
-keepclassmembers class com.ikko.aura.player.smart.** {
    *** Companion;
}
-keepclasseswithmembers class com.ikko.aura.player.smart.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# YouTube Music source (NewPipeExtractor and its JavaScript engine, which it loads by reflection).
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn javax.lang.model.**
-dontwarn jdk.**
