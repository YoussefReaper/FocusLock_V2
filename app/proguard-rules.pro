# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Shrink, don't rename. FocusLock is sideloaded and supported by hand from
# problem reports (see Diagnostics), so a stack trace has to be readable as it
# arrives, without a mapping file kept for every release. The size win is from
# removing unused code, which renaming barely adds to.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# NewPipeExtractor (personal build): Rhino runs YouTube's player script by
# reflection, and its pattern classes are loaded by name.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
