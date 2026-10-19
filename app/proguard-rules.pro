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

# Tink (used by the UnifiedPush connector for Web Push decryption) refers to optional libraries
# that aren't in the app.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-dontwarn com.google.crypto.tink.**
-keep class com.vknxworks.proxctrl.data.notify.ProxPushService { *; }
