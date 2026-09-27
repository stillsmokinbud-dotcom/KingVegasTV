# King Vegas TV — R8 rules. R8 makes Compose noticeably smoother (optimized, smaller code).
# Our own classes are kept whole (settings/serialization use them by name).
-keep class com.novatv.app.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
# Libraries bring their own rules; don't fail the build on missing optional classes.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-ignorewarnings
