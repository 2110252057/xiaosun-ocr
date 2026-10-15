# ============================================================================
# 小孙OCR · ProGuard / R8 规则
# ----------------------------------------------------------------------------
# 说明：本文件此前是 AGP 生成的默认模板，不含任何项目规则。
#       当前 release 的 minifyEnabled = false，规则暂不生效；
#       但一旦开启混淆，缺少 JNI keep 规则会直接导致
#       UnsatisfiedLinkError（静态符号绑定，见下），故预先补全。
# ============================================================================

# ---------------------------------------------------------------------------
# 1. JNI 桥接类 —— 绝对不能混淆
# ---------------------------------------------------------------------------
# native 库（libnjocr.so）使用**静态符号绑定**：没有 JNI_OnLoad、无法动态注册，
# 符号名形如 Java_com_xiaosun_ocr_OCRService_nativeProcessBitmap。
# 包名 / 类名 / 方法名任意一处被混淆，符号就对不上，调用即崩。
-keep class com.xiaosun.ocr.OCRService {
    <methods>;
}

# 兜底：保留所有持有 native 方法的类（含将来新增的）
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# 2. 系统按名字反射实例化的组件
# ---------------------------------------------------------------------------
# Activity / Service / BroadcastReceiver 由系统依据 Manifest 中的类名反射创建，
# 混淆后会 ClassNotFoundException。
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# 自定义 View 经 XML 引用时，带 Context,AttributeSet 的构造器必须保留。
# （本项目目前为纯程序化 UI，保留以兼容后续改动）
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---------------------------------------------------------------------------
# 3. 需要保留的元数据属性
# ---------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
# 保留行号，便于线上崩溃栈还原
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# 4. 第三方库
# ---------------------------------------------------------------------------
# Gson 通过反射读写字段，需保留类成员与泛型签名；
# （本项目当前直接用 JsonObject/JsonArray，无自定义模型，保留以防后续新增）
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer