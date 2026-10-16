package com.bldf1.kugoulite.plus

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.Future

/**
 * GDT（腾讯优量汇）广告请求「成功空响应」拦截器 —— 零闪现的关键一环
 *
 * 背景：酷狗里的 GDT 广告 SDK **不走宿主 okhttp3**，而是用自带网络层
 * （实测 DNS 拦截栈：com.qq.e.comm.net.a.a.c.c$1.run → a.a.c.e.c →
 * InetAddress.getAllByName）。我们原先只能在 DNS 层让它"请求失败"，
 * 而"失败 ≠ 无广告"：SDK 收到错误仍可能 inflate 占位容器/走降级填充，
 * 于是出现空白与闪现。
 *
 * 反编译 classes2.dex 得到的 GDT 网络 API：
 *   com.qq.e.comm.net.NetworkClientImpl.submit(Request[, priority][, callback]…)
 *   com.qq.e.comm.net.NetworkCallBack.onResponse(Request, Response) / onException(Exception)
 *   com.qq.e.comm.net.rr.Response  ← **public interface**（可动态代理伪造）
 *       getStatusCode() / getStringContent([String]) / getBytesContent() /
 *       getStreamContent() / close()，常量 HTTP_OK / HTTP_NO_CONTENT / HTTP_UNKNOWN
 *
 * 策略：命中广告域名的请求根本不发出，直接回调 onResponse 并给出
 * 200 + `{}` 的空响应（或 Future.get() 返回它）。SDK 解析出"无广告"，
 * 压根不会创建广告视图 → 无空白、零闪现。
 */
object GdtAdBlocker {

    private const val CLIENT = "com.qq.e.comm.net.NetworkClientImpl"
    private const val REQUEST = "com.qq.e.comm.net.rr.Request"
    private const val RESPONSE = "com.qq.e.comm.net.rr.Response"
    private const val CALLBACK = "com.qq.e.comm.net.NetworkCallBack"

    // 判定为广告请求的域名关键词。实测（initResponse 抓到的真实域名）：
    //   tangram.e.qq.com   = GDT SDK 配置/广告下发（最关键，掐掉=无广告）
    //   i.gtimg.cn         = 素材 CDN
    //   sdkreport.e.qq.com / us.l.qq.com = 曝光与统计上报
    // 注意：GDT 走自己的 rr/Request+HttpURLConnection，不走宿主 okhttp3
    private val AD_HOST_KEYS = arrayOf(
        "gdt.qq.com", "gdtimg.com", "adsmind",
        "e.qq.com",        // tangram/sdkreport 等 GDT 主域
        "gtimg.cn",        // 素材 CDN
        "l.qq.com",        // 上报
        "pangle.io", "pangle.cn", "openadsdk",
        "pglstatp-toutiao.com", "pangolin-sdk-toutiao.com",
        "toutiao.com", "toutiaoimg.com", "snssdk.com", "ctobsnssdk.com"
    )

    @Volatile
    private var installed = false

    @Volatile
    private var blocked = 0

    /** 是否开启（跟随网络净化开关） */
    @Volatile
    private var enabled = true

    fun setEnabled(v: Boolean) {
        enabled = v
    }

    private fun isAdUrl(url: String): Boolean =
        AD_HOST_KEYS.any { url.contains(it, ignoreCase = true) }

    /**
     * 伪造 rr.Response：200 + 空 JSON。
     * 未知方法按返回类型兜底：SDK 可能调基本类型方法（如 hashCode/内容长度），
     * 返回 null 会在自动拆箱时 NPE 进入 SDK 内部。
     */
    private fun fakeResponse(respCls: Class<*>, reqCls: Class<*>): Any? = try {
        val payload = "{}"
        val bytes = payload.toByteArray()
        Proxy.newProxyInstance(
            respCls.classLoader, arrayOf(respCls),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getStatusCode" -> 200
                    "getStringContent" -> payload
                    "getBytesContent" -> bytes
                    "getStreamContent" -> ByteArrayInputStream(bytes)
                    "close" -> null
                    "toString" -> "GdtFakeResponse(200,{})"
                    else -> when (method.returnType) {
                        java.lang.Integer.TYPE -> 0
                        java.lang.Boolean.TYPE -> false
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }
            }
        ).also { require(reqCls.isInterface || true) }
    } catch (_: Throwable) {
        null
    }

    /**
     * 伪造 Future：get() 直接给出响应（返回类型兜底同上）
     */
    private fun fakeFuture(futureCls: Class<*>, response: Any): Any? = try {
        Proxy.newProxyInstance(
            futureCls.classLoader, arrayOf(futureCls),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "get" -> response
                    "cancel" -> java.lang.Boolean.FALSE
                    "isCancelled" -> java.lang.Boolean.FALSE
                    "isDone" -> java.lang.Boolean.TRUE
                    else -> when (method.returnType) {
                        java.lang.Integer.TYPE -> 0
                        java.lang.Boolean.TYPE -> false
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }
            }
        )
    } catch (_: Throwable) {
        null
    }

    fun install(xposed: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        try {
            val clientCls = Class.forName(CLIENT, false, classLoader)
            val reqCls = Class.forName(REQUEST, false, classLoader)
            val respCls = Class.forName(RESPONSE, false, classLoader)
            val cbCls = Class.forName(CALLBACK, false, classLoader)
            val futureCls = Class.forName("java.util.concurrent.Future", false, classLoader)

            val submits = clientCls.declaredMethods.filter { it.name == "submit" }
            if (submits.isEmpty()) {
                ModuleLog.log("GDT广告拦截: NetworkClientImpl 无 submit 方法，跳过")
                return
            }

            // 预造一个响应样例，验证代理可构造（失败则整体放弃，不影响其它逻辑）
            val sample = fakeResponse(respCls, reqCls)
            if (sample == null) {
                ModuleLog.log("GDT广告拦截: rr.Response 代理构造失败，跳过")
                return
            }

            val getUrl = try {
                reqCls.getMethod("getUrlWithParas")
            } catch (_: Throwable) {
                reqCls.getMethod("getUrl")
            }
            val getUrlSimple = try {
                reqCls.getMethod("getUrl")
            } catch (_: Throwable) {
                null
            }
            val onResponse = cbCls.getMethod("onResponse", reqCls, respCls)

            for (m in submits) {
                val returnsFuture = m.returnType != Void.TYPE
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (!enabled) return@intercept chain.proceed()
                        val req = chain.getArg(0) ?: return@intercept chain.proceed()
                        var url: String? = null
                        try {
                            url = (getUrl.invoke(req) ?: getUrlSimple?.invoke(req))?.toString()
                        } catch (_: Throwable) {
                        }
                        if (url == null || !isAdUrl(url)) return@intercept chain.proceed()

                        val resp = fakeResponse(respCls, reqCls)
                        if (resp == null) return@intercept chain.proceed()

                        // 找到回调参数（submit 的重载中位置不定）
                        var cb: Any? = null
                        for (i in 1 until m.parameterTypes.size) {
                            val a = chain.getArg(i)
                            if (a != null && cbCls.isAssignableFrom(a.javaClass)) {
                                cb = a
                                break
                            }
                        }
                        if (cb != null) {
                            try {
                                onResponse.invoke(cb, req, resp)
                            } catch (t: Throwable) {
                                ModuleLog.log("GDT广告拦截: 回调 onResponse 异常 $t")
                            }
                        }
                        if (blocked < 10) {
                            blocked++
                            ModuleLog.log("GDT广告拦截(空响应·零闪现): ${url.take(90)}")
                        }
                        if (returnsFuture) fakeFuture(futureCls, resp) else null
                    }
            }
            installed = true
            ModuleLog.log("GDT广告拦截: NetworkClientImpl.submit 安装成功(${submits.size} 个重载)")

            // 任务执行点：NetworkClientImpl$TaskCallable.call() —— 在这里拦
            // 连网络请求都不发出（最高级：零流量 + 零视图）
            installTaskCallableHook(xposed, classLoader, reqCls, respCls)

            // 真正的 HTTP 响应生成点：rr.Request.initResponse(HttpURLConnection)
            // （实现类 PlainRequest / S2SSRequest）。无论上层走哪条链路，
            // 只要用 rr/Request 就必经此处 —— 在这里注入空响应最稳。
            installInitResponseHook(xposed, classLoader, reqCls, respCls)
        } catch (t: Throwable) {
            ModuleLog.log("GDT广告拦截: 安装失败(降级DNS): $t")
        }
    }

    /**
     * Hook NetworkClientImpl$TaskCallable.call()：命中广告请求时直接返回
     * 空响应，连 TCP 连接都不建立（零流量）。
     * TaskCallable 内部持有 rr.Request（字段名混淆），反射取即可。
     */
    private fun installTaskCallableHook(
        xposed: XposedModule, classLoader: ClassLoader,
        reqCls: Class<*>, respCls: Class<*>
    ) {
        try {
            val cls = Class.forName(
                "com.qq.e.comm.net.NetworkClientImpl\$TaskCallable", false, classLoader
            )
            val call = cls.declaredMethods.firstOrNull {
                it.name == "call" && it.parameterTypes.isEmpty()
            } ?: run {
                ModuleLog.log("GDT广告拦截: TaskCallable.call 未找到")
                return
            }
            val reqFields = cls.declaredFields.filter { f ->
                reqCls.isAssignableFrom(f.type) || f.type.isAssignableFrom(reqCls)
            }
            val getUrl = try {
                reqCls.getMethod("getUrlWithParas")
            } catch (_: Throwable) {
                reqCls.getMethod("getUrl")
            }
            reqFields.forEach { it.isAccessible = true }
            xposed.hook(call)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled) return@intercept chain.proceed()
                    var url: String? = null
                    for (f in reqFields) {
                        val req = try {
                            f.get(chain.thisObject)
                        } catch (_: Throwable) {
                            null
                        } ?: continue
                        url = try {
                            getUrl.invoke(req)?.toString()
                        } catch (_: Throwable) {
                            null
                        }
                        if (url != null) break
                    }
                    if (url == null || !isAdUrl(url)) return@intercept chain.proceed()
                    val resp = fakeResponse(respCls, reqCls)
                        ?: return@intercept chain.proceed()
                    if (blocked < 10) {
                        blocked++
                        ModuleLog.log("GDT广告拦截(任务层·零流量): ${url.take(90)}")
                    }
                    resp
                }
            ModuleLog.log("GDT广告拦截: TaskCallable.call Hook 安装成功")
        } catch (t: Throwable) {
            ModuleLog.log("GDT广告拦截: TaskCallable Hook 失败: $t")
        }
    }

    private fun installInitResponseHook(
        xposed: XposedModule, classLoader: ClassLoader,
        reqCls: Class<*>, respCls: Class<*>
    ) {
        var ok = 0
        for (impl in listOf(
            "com.qq.e.comm.net.rr.PlainRequest",
            "com.qq.e.comm.net.rr.S2SSRequest"
        )) {
            try {
                val cls = Class.forName(impl, false, classLoader)
                val m = cls.getDeclaredMethod(
                    "initResponse", java.net.HttpURLConnection::class.java
                )
                val getUrl = try {
                    reqCls.getMethod("getUrlWithParas")
                } catch (_: Throwable) {
                    reqCls.getMethod("getUrl")
                }
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (!enabled) return@intercept chain.proceed()
                        val req = chain.thisObject ?: return@intercept chain.proceed()
                        val url = try {
                            getUrl.invoke(req)?.toString()
                        } catch (_: Throwable) {
                            null
                        }
                        if (url == null || !isAdUrl(url)) return@intercept chain.proceed()
                        val resp = fakeResponse(respCls, reqCls)
                        if (resp == null) return@intercept chain.proceed()
                        if (blocked < 10) {
                            blocked++
                            ModuleLog.log("GDT广告拦截(空响应·零闪现): ${url.take(90)}")
                        }
                        resp
                    }
                ok++
            } catch (t: Throwable) {
                ModuleLog.log("GDT广告拦截: $impl.initResponse 未Hook到: $t")
            }
        }
        ModuleLog.log("GDT广告拦截: initResponse Hook 安装 $ok/2")
    }
}
