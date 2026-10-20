# 保持 kotlinx.serialization 生成的序列化器（当前 release 未开启混淆，保留规则以便后续启用）。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.workbuddy2api.panel.**$$serializer { *; }
-keepclassmembers class com.workbuddy2api.panel.** { *** Companion; }
-keepclasseswithmembers class com.workbuddy2api.panel.** { kotlinx.serialization.KSerializer serializer(...); }
