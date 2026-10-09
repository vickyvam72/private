-keep class androidx.room.** { *; }
-keep class com.lumisignal.idxscreener.data.** { *; }
-dontwarn org.json.**

# Hidden IDX WebView bridge: JavaScript calls LumiBridge.onResult by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
