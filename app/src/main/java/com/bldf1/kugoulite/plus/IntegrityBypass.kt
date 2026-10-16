package com.bldf1.kugoulite.plus

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 防检测：签名一致性 + 更新检测屏蔽
 *
 * 1. 签名一致性：Hook getPackageInfo 两个重载（String,int 与 String,PackageInfoFlags），
 *    自包签名以"首见值为准"，防止运行期多次查询返回不一致结果触发应用自校验。
 *    另补堵 getCurrentPackageInfo / getInstalledPackages 绕过路径。
 * 2. 更新检测：由 PopupBlocker 在 block_update 开启时拦截更新弹窗/活动页。
 *
 * 自检：ClaimWorker 启动时连续两次查询自包签名，验证 Hook 拦截与缓存一致性。
 */
object IntegrityBypass {

    private const val TARGET_PACKAGE = "com.kugou.android.lite"
    private const val PM_IMPL_CLASS = "android.app.ApplicationPackageManager"
    private const val FLAGS_CLASS = "android.content.pm.PackageManager\$PackageInfoFlags"

    // 首见签名缓存
    @Volatile
    private var cachedSignatures: Array<Signature>? = null

    // 调试计数：拦截次数（自包查询），前 10 次打日志避免刷屏
    private var interceptedCalls = 0

    fun install(xposed: XposedModule) {
        var installed = 0

        // 重载 1: getPackageInfo(String, int)
        if (hookGetPackageInfo(
                xposed, arrayOf(String::class.java, Int::class.javaPrimitiveType), "int"
            )
        ) installed++

        // 重载 2: getPackageInfo(String, PackageInfoFlags) — Android 13+
        try {
            val flagsClass = Class.forName(FLAGS_CLASS)
            if (hookGetPackageInfo(xposed, arrayOf(String::class.java, flagsClass), "flags")) {
                installed++
            }
        } catch (_: Throwable) {
            ModuleLog.log("防检测: PackageInfoFlags 重载不存在，跳过")
        }

        // 绕过路径补堵：
        // - getCurrentPackageInfo：Android 13+ 自包查询可绕过 getPackageInfo
        // - getInstalledPackages：宿主可遍历列表拿到自包签名
        installed += hookCurrentPackageInfo(xposed)
        installed += hookGetInstalledPackages(xposed)

        if (installed > 0) {
            ModuleLog.log("防检测(签名一致性)安装成功: $installed 个重载")
        } else {
            ModuleLog.log("防检测安装失败: 无可用重载")
        }
    }

    private fun hookGetPackageInfo(
        xposed: XposedModule, paramTypes: Array<Class<*>?>, overloadName: String
    ): Boolean {
        return try {
            val pmClass = Class.forName(PM_IMPL_CLASS)
            val gpi = Reflector.findMethod(pmClass, "getPackageInfo", *paramTypes)
            if (gpi == null) {
                ModuleLog.log("防检测: getPackageInfo($overloadName) 未找到方法")
                return false
            }
            xposed.hook(gpi)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val pkg = chain.getArg(0) as? String
                        if (pkg == TARGET_PACKAGE) {
                            interceptedCalls++
                            if (interceptedCalls <= 10) {
                                ModuleLog.log(
                                    "防检测: 拦截 getPackageInfo($overloadName) 第${interceptedCalls}次"
                                )
                            }
                            val ctx = chain.thisObject as? Context
                            if (ctx != null && Settings.isFeature(ctx, Settings.KEY_BYPASS_SIGNATURE)) {
                                stabilizeSignatures(result)
                            }
                        }
                    } catch (_: Exception) {
                        // 静默
                    }
                    result
                }
            true
        } catch (e: Exception) {
            ModuleLog.log("防检测 Hook 失败($overloadName): $e")
            false
        }
    }

    /**
     * Hook getCurrentPackageInfo（Android 13+，自包签名查询的绕过路径）
     */
    private fun hookCurrentPackageInfo(xposed: XposedModule): Int {
        var ok = 0
        val paramSets = listOf(
            arrayOf<Class<*>>(String::class.java, Int::class.javaPrimitiveType!!),
            try {
                arrayOf<Class<*>>(String::class.java, Class.forName(FLAGS_CLASS))
            } catch (_: Throwable) {
                return ok
            },
        )
        for (params in paramSets) {
            try {
                val pmClass = Class.forName(PM_IMPL_CLASS)
                val m = Reflector.findMethod(pmClass, "getCurrentPackageInfo", *params) ?: continue
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val ctx = chain.thisObject as? Context
                            if (ctx != null && Settings.isFeature(ctx, Settings.KEY_BYPASS_SIGNATURE)) {
                                stabilizeSignatures(result)
                            }
                        } catch (_: Exception) {
                            // 静默
                        }
                        result
                    }
                ok++
            } catch (_: Throwable) {
            }
        }
        if (ok > 0) ModuleLog.log("防检测: getCurrentPackageInfo 补堵 $ok 个重载")
        return ok
    }

    /**
     * Hook getInstalledPackages：结果列表中自包的签名同样替换，
     * 防宿主遍历列表绕过单包查询 hook（含 ParceledListSlice 解包）。
     */
    private fun hookGetInstalledPackages(xposed: XposedModule): Int {
        return try {
            val pmClass = Class.forName(PM_IMPL_CLASS)
            var ok = 0
            for (m in pmClass.declaredMethods) {
                if (m.name != "getInstalledPackages") continue
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val ctx = chain.thisObject as? Context
                            if (ctx == null || !Settings.isFeature(ctx, Settings.KEY_BYPASS_SIGNATURE)) {
                                return@intercept result
                            }
                            // List<PackageInfo>（新版可能 wrapped 为 ParceledListSlice）
                            val list: List<*>? = when (result) {
                                is List<*> -> result
                                else -> try {
                                    val gl = result?.javaClass?.getMethod("getList")
                                    gl?.invoke(result) as? List<*>
                                } catch (_: Throwable) {
                                    null
                                }
                            }
                            list?.forEach { pi ->
                                stabilizeSignatures(pi)
                            }
                        } catch (_: Exception) {
                            // 静默
                        }
                        result
                    }
                ok++
            }
            if (ok > 0) ModuleLog.log("防检测: getInstalledPackages 补堵 $ok 个重载")
            ok
        } catch (e: Exception) {
            ModuleLog.log("防检测: getInstalledPackages Hook 失败: $e")
            0
        }
    }

    private fun stabilizeSignatures(result: Any?) {
        val pi = result as? PackageInfo ?: return
        if (pi.packageName != TARGET_PACKAGE) return
        val signatures = pi.signatures ?: return
        if (signatures.isEmpty()) return

        val cached = cachedSignatures
        if (cached == null) {
            cachedSignatures = signatures.copyOf()
            ModuleLog.log("防检测: 已缓存首见签名 ${signatures.size}个 (hash=${signatureHash(pi)})")
            return
        }
        if (cached.size != signatures.size) {
            // 签名数量变化（宿主升级/查询方式变化）：旧缓存永久失真，
            // 以新见值为准重建缓存，否则后续每次替换都会被跳过
            cachedSignatures = signatures.copyOf()
            ModuleLog.log("防检测: 签名数量变化 ${cached.size}→${signatures.size}，重建缓存")
            return
        }
        var replaced = 0
        for (i in cached.indices) {
            if (signatures[i] != cached[i]) {
                signatures[i] = cached[i]
                replaced++
            }
        }
        if (replaced > 0) {
            ModuleLog.log("防检测: 替换 $replaced 处签名差异 (hash=${signatureHash(pi)})")
        }
    }

    /**
     * 自检：连续两次查询自包签名（GET_SIGNATURES），
     * 第一次触发缓存、第二次应命中缓存，两次 hash 必须一致。
     */
    fun selfTest(ctx: Context) {
        try {
            val pm = ctx.packageManager
            val first = pm.getPackageInfo(TARGET_PACKAGE, PackageManager.GET_SIGNATURES)
            val h1 = signatureHash(first)
            val second = pm.getPackageInfo(TARGET_PACKAGE, PackageManager.GET_SIGNATURES)
            val h2 = signatureHash(second)
            ModuleLog.log("防检测自检: 第一次=$h1 第二次=$h2 一致=${h1 == h2}")
        } catch (e: Exception) {
            ModuleLog.log("防检测自检失败: $e")
        }
    }

    private fun signatureHash(pi: PackageInfo?): String {
        val signatures = pi?.signatures ?: return "null"
        if (signatures.isEmpty()) return "empty"
        return "sig" + signatures[0].hashCode()
    }
}
