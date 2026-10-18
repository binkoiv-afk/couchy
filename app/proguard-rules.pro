# Media3 and Room ship their own consumer rules; nothing extra is needed yet.

# NewPipe Extractor: YouTube parsing and the JavaScript engine that deciphers stream links
# work through reflection.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class com.grack.nanojson.** { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn com.google.re2j.**

# The FFmpeg sound decoder is found by name at run time.
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# libVLC calls back into its Java classes from native code.
-keep class org.videolan.libvlc.** { *; }
