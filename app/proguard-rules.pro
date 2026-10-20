# kotlinx.serialization models (library ships its own rules; these keep our DTOs and routes intact).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class com.vknxworks.proxctrl.**$$serializer { *; }
-keepclassmembers class com.vknxworks.proxctrl.** {
    *** Companion;
}
-keepclasseswithmembers class com.vknxworks.proxctrl.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-dontwarn javax.annotation.**
# Push from ntfy: created by Android from their class names.
-keep class com.vknxworks.proxctrl.data.notify.PushReceiver { *; }
-keep class com.vknxworks.proxctrl.data.notify.RaiseToForegroundService { *; }

# The console pages call the app through @JavascriptInterface methods; keep them by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# The console pages call ConsoleBridge by method name; keep it whole, with its annotations.
-keep class com.vknxworks.proxctrl.ui.console.ConsoleBridge { public *; }
-keepattributes RuntimeVisibleAnnotations
