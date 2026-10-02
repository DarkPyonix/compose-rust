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

# The boundary is reached by name from native code, in both directions.
#
# The shims bind to the class and method names the schema generated, and the runtime
# resolves them as strings at load time, so a rename or a removal is invisible to the
# shrinker and shows up as an UnsatisfiedLinkError on the first call instead.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class dioxus.compose.ui.platform.HostBridge {
    public static void onFrameRequested();
}
