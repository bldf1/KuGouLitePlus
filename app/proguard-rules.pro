# ============================================================================
# R8 / ProGuard 规则（release 已开启 minifyEnabled + shrinkResources）
#
# 背景：Round 35 引入词幕(Lyricon) provider SDK 后，debug APK 达 10.03MB；
#       参考项目 OneDongua/KugouLiteProvider 的 release 包只有 0.42MB
#       （单 dex 439KB，R8 混淆后全包只剩 430 个类型引用）。
#       差距不在「依赖多」，而在 release 从未开启裁剪（minifyEnabled=false）。
#
# 原则：**凡是被「非代码」方式引用的类，一律保名保成员**。本模块有且只有三类：
#   ① 由 META-INF/xposed/java_init.list 的类名字符串加载的入口类
#   ② 跨进程被词幕中心服务（跑在 SystemUI）调用的 AIDL / Binder 类
#   ③ 走 kotlinx.serialization 运行期反射的 @Serializable 模型与其 $$serializer
# ============================================================================

# ---------------------------------------------------------------------------
# ① Xposed 模块入口
# ---------------------------------------------------------------------------
# 框架读取 META-INF/xposed/java_init.list 里的**类名字符串**反射加载并 cast 到
# XposedModule ⇒ 类名与成员名都不能动。
-keep class com.bldf1.kugoulite.plus.MainHook { *; }

# libxposed API 是 compileOnly（运行时由框架提供）⇒ R8 看不到实现，需忽略缺失类
-dontwarn io.github.libxposed.**
-keep interface io.github.libxposed.** { *; }
-keep class io.github.libxposed.** { *; }

# 保险：实现 XposedModule 的类保留名字（框架按接口回调，正常走 invoke-interface 不需要，
# 但保留名字只增加极小体积，换来「日志/反编译里看得懂」的确定性）
-keepnames class * implements io.github.libxposed.api.XposedModule

# ---------------------------------------------------------------------------
# ② 词幕(Lyricon) 提供器 SDK —— 整体保留（不裁剪、不混淆）
# ---------------------------------------------------------------------------
# 理由：
#  1) AIDL（IProviderBinder / IRemotePlayer / IRemoteService）由**另一个进程**
#     （SystemUI 里的词幕中心服务）通过 Binder 调用，Stub/Proxy 的类结构不能被动；
#     且 SDK 内部有按接口名/描述符取对象的地方。
#  2) ProviderInfo / ProviderLogo / ProviderMetadata / Song 等模型走 kotlinx.serialization，
#     `$$serializer`、`Companion`、`Creator`(Parcelize) 全是**运行期按名字反射查找**的。
#  3) 整个 SDK 只有 ~115KB class —— 全量保留换来的是「绝不因混淆静默失效」，
#     真正的体积大头（androidx / kotlin stdlib / coroutines）本来就靠可达性裁剪。
-keep class io.github.proify.lyricon.** { *; }
-keepclassmembers class io.github.proify.lyricon.** { *; }

# ---------------------------------------------------------------------------
# ③ kotlinx.serialization 保底
# ---------------------------------------------------------------------------
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses
-dontwarn kotlinx.serialization.**

# ---------------------------------------------------------------------------
# 其它
# ---------------------------------------------------------------------------
# 保留 Context 构造函数：宿主类 hook 场景下需要按签名匹配构造函数
-keepclassmembers class * {
    public <init>(android.content.Context);
}

# 保留行号，release 崩溃栈仍可定位到源码行
-keepattributes SourceFile,LineNumberTable
# 但把源文件名抹成常量，避免暴露内部文件名
-renamesourcefileattribute SourceFile
