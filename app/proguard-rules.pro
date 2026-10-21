# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.ntpx.alchemist.**$$serializer { *; }
-keepclassmembers class com.ntpx.alchemist.** { *** Companion; }
