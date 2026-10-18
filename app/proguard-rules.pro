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
