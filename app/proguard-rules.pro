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

# Release builds drop verbose, debug and info logging (from the app and its libraries), so nothing
# from them can end up in the phone's log.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# WorkManager keeps its job list in a Room 2.6.1 database, and Room creates the generated
# WorkDatabase_Impl by reflection through its no-argument constructor. Room 2.6.1's own rule
# (-keep class * extends androidx.room.RoomDatabase) names no members, and the R8 in Android
# Gradle Plugin 9 no longer keeps a constructor for such a rule, so without this line the app
# would crash at start (NoSuchMethodException: WorkDatabase_Impl.<init>). Same rule Room 2.7 ships.
-keep class * extends androidx.room.RoomDatabase { void <init>(); }
