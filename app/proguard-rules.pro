# ---- R8 极限压缩：只保留 Manifest 组件与运行时可达代码 ----
-allowaccessmodification
-overloadaggressively
-dontusemixedcaseclassnames
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# WebView 嗅探回调（若被 JS 调用，方法名不能混淆）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ijkplayer：native 层按类名/方法名注册 JNI，混淆会导致 JNI_OnLoad 返回 JNI_ERR
# （bilibili/ijkplayer issue #3471），必须整包 keep
-keep class tv.danmaku.ijk.media.** { *; }
-keep class tv.danmaku.ijk.player.ffmpeg.FFmpegApi { *; }

# 平台 / 可选依赖的缺失类警告，R8 full mode 下严格检查，这里统一放行
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.jetbrains.**
-dontwarn androidx.**
-dontwarn android.support.**
-dontwarn java.lang.invoke.**
-dontwarn javax.**
-dontwarn org.w3c.dom.**
-dontwarn org.xml.sax.**
-dontwarn org.json.**
-dontwarn com.downx.app.**
-keep class com.arthenica.ffmpegkit.** { *; }

-dontwarn com.arthenica.smartexception.**
-keep class com.arthenica.smartexception.** { *; }
