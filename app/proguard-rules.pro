# R8 rules of the release build (and of QA, which is the release build under another id).
#
# R8 runs in full mode (android.enableR8.fullMode in gradle.properties) on top of proguard-android-optimize.txt.
# Most libraries bring their own rules, merged in automatically, and they are not repeated here:
#   - Hilt / Dagger: generated components and the ViewModel maps (hilt-android, dagger, generated rules).
#   - Room: the generated *_Impl databases, found by name (room-runtime keeps their constructor).
#   - kotlinx.serialization: the generated serializers and the companions that hold them, in full mode too.
#     Every @Serializable class of the app is read through its generated serializer, whose field names are
#     string constants: renaming the Kotlin class or its properties does not change the JSON on disk or on the wire.
#   - Media3: the extension renderers it loads by reflection, and the @UsedByNative members.
#   - Compose: the runtime, ui and foundation rules. Compose needs nothing kept by hand — composable lambdas are
#     ordinary classes the compiler plugin creates and calls directly, so shrinking them is what makes the UI fast.
#   - Coroutines, DataStore, lifecycle, navigation, jsoup, okio.
# No Koin, Gson or Moshi in the app: nothing is injected or deserialised by reflection on the app's own classes.
#
# Only what those do not cover is below, each kept as narrowly as it can be. Check a change with
# app/build/outputs/mapping/release/{seeds,usage}.txt (what is kept, what is removed).

# --- Readable stack traces -----------------------------------------------------------------------------------------
# Line numbers survive, the file names are folded into one: a crash read from logcat retraces with the mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Smaller dex ---------------------------------------------------------------------------------------------------
# Renamed classes all go into one package: shorter descriptors in the dex, and more of it on the same pages.
# Classes kept by name (JNI, the manifest's components, the rules below) are not moved.
-repackageclasses

# --- The app's JNI (libaura_analysis: mel spectrogram, tempo, vocals) ----------------------------------------------
# Bound by symbol name (Java_com_ikko_aura_player_smart_<Class>_<method>), so the classes holding native methods and
# those methods keep their names. The C++ side never calls back into Java, so nothing else of theirs needs keeping.
-keepclasseswithmembernames,includedescriptorclasses class com.ikko.aura.** {
    native <methods>;
}

# --- ONNX Runtime (Automix models) ---------------------------------------------------------------------------------
# Its native side looks its own Java classes and methods up by name through JNI GetMethodID, so renaming any of them
# aborts the VM the first time a model runs (observed as a JniAbort inside convertToTensorInfo). Its packaged
# consumer rules do not cover the paths Automix exercises.
-keep class ai.onnxruntime.** { *; }

# --- FFmpeg decoders (nextlib) -------------------------------------------------------------------------------------
# libmedia3ext.so calls this one back through GetMethodID to grow its output. Media3's @UsedByNative rule keeps it
# today; kept here by name as well so a release of nextlib that drops the annotation does not crash the decoder.
-keepclassmembers class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioDecoder {
    java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}

# --- YouTube Music source (NewPipeExtractor) -----------------------------------------------------------------------
# The extractor itself is plain code, called directly: R8 may shrink and rename it. Two things in it are reached by
# name only, as NewPipe's own app keeps them:
#   - the "x minutes ago" patterns, one class per language, loaded with Class.forName from the language code;
#   - Rhino, the JavaScript engine that runs YouTube's player code, which loads its own parts by reflection.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
# Desktop-JVM APIs Rhino and the extractor refer to on paths Android never takes.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn javax.lang.model.**
-dontwarn jdk.**
-dontwarn org.mozilla.javascript.tools.**

# The playlist assistant's JNI bridge: llama_bridge.cpp exports Java_com_ikko_aura_ai_slm_LlamaBridge_* by name, so the
# class and its native methods keep theirs.
-keepclasseswithmembernames class com.ikko.aura.ai.slm.LlamaBridge {
    native <methods>;
}
