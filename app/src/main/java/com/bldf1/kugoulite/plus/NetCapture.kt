package com.bldf1.kugoulite.plus

import java.net.InetAddress

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * OkHttp 网络请求捕获器（libxposed 拦截器链模型）
 *
 * 通过 Hook okhttp3.Request$Builder.build() 方法，
 * 从酷狗概念版的网络请求 URL 中提取登录凭证
 * （token, userid, dfid, mid, appid, clientver）
 *
 * 这些凭证用于后续调用酷狗官方接口领取每日VIP。
 */
object NetCapture {

    private const val OKHTTP_BUILDER = "okhttp3.Request\$Builder"

    // 广告请求黑洞地址（连接立即失败，SDK 走自带失败路径）
    private const val BLACKHOLE_URL = "http://127.0.0.1:9/"

    // 广告域名关键词：命中即拦截（优先走"空响应"，失败才降级黑洞）
    private val BLOCK_AD_HOSTS = arrayOf(
        "gdt.qq.com",   // 腾讯优量汇 GDT
        "gdtimg.com",   // GDT 素材
        "adsmind",      // GDT 素材节点
        "pangle.io",    // 穿山甲
        "pangle.cn",    // 穿山甲
        "openadsdk",    // 穿山甲
        "pglstatp-toutiao.com", "pangolin-sdk-toutiao.com",
        "toutiao.com", "toutiaoimg.com",
        "snssdk.com", "ctobsnssdk.com",
        // ⭐ R73：**酷狗自家广告网关**（按"路径关键词"拦，不是域名！）
        // 真机实锤：播放页弹窗/视频广告的接口是
        //   https://gateway.kugou.com/ads.gateway/v2/lite_v_youth_player_popup
        // 视图层拦 PlayerAdMaskView 已经太晚 —— 日志是 `已绘制=是 严格=是 1220x2169`，
        // 也就是整屏蒙层已经画出来（用户实锤"打开播放页又触发视频广告"）。
        // 走这里的"假成功空响应" ⇒ SDK 解析出无广告 ⇒ **压根不创建广告视图**（零闪现）。
        // ⚠️ 只能写路径片段，绝不能写 `gateway.kugou.com` —— 那是主 API 域，整域拦会毁掉所有功能。
        "ads.gateway",  // 酷狗广告网关（Banner / 播放页弹窗 / 视频广告等）
    )

    @Volatile
    private var blockAdNet = true

    /**
     * 设置广告请求拦截开关（面板/启动时同步）
     */
    fun setBlockAdNet(value: Boolean) {
        blockAdNet = value
    }

    private fun isBlockedAdHost(urlStr: String): Boolean =
        BLOCK_AD_HOSTS.any { urlStr.contains(it, ignoreCase = true) }

    /**
     * 把请求改写到黑洞地址（通过 Builder 反射重建，兼容 okhttp3/4）
     */
    private fun rewriteToBlackhole(request: Any): Any? = try {
        val builder = request.javaClass.getMethod("newBuilder").invoke(request)
        builder.javaClass.getMethod("url", String::class.java).invoke(builder, BLACKHOLE_URL)
        builder.javaClass.getMethod("build").invoke(builder)
    } catch (_: Exception) {
        null
    }

    @Volatile
    private var installed = false
    @Volatile
    private var lastToken: String? = null

    @Volatile
    private var adBlockedCount = 0

    // 广告域名后缀（DNS 层拦截：解析直接返回黑洞地址，SDK 请求瞬间失败走离线降级）
    private val BLOCKED_AD_DOMAINS = arrayOf(
        "gdt.qq.com",                 // 腾讯优量汇 GDT 全节点
        "gdtimg.com",                 // GDT 素材 CDN
        "pangle.cn", "pangle.io",     // 穿山甲 Pangle
        "pglstatp-toutiao.com",       // 穿山甲素材 CDN
        "pangolin-sdk-toutiao.com",   // 穿山甲 API
        "toutiao.com", "toutiaoimg.com", // 字节系域名/CDN
        "snssdk.com", "ctobsnssdk.com", // 字节日志/统计
    )

    private fun isAdHost(host: String): Boolean {
        val h = host.lowercase()
        for (d in BLOCKED_AD_DOMAINS) {
            if (h == d || h.endsWith(".$d")) return true
        }
        return false
    }

    @Volatile
    private var dnsHookInstalled = false

    @Volatile
    private var dnsBlockedCount = 0

    /**
     * DNS 层广告拦截：Hook InetAddress.getByName/getAllByName（进程级），
     * 广告域名解析返回 0.0.0.0 —— 连接立即失败，SDK 走离线降级路径。
     * 所有网络库（OkHttp/Cronet/HttpURLConnection/自有实现）都经过 DNS，
     * 是唯一的进程级统一拦截点。回调仅做字符串后缀比对，热路径零开销。
     */
    fun installDnsBlock(xposed: XposedModule) {
        if (dnsHookInstalled) return
        dnsHookInstalled = true
        try {
            val inetClass = InetAddress::class.java
            for (m in listOf(
                inetClass.getDeclaredMethod("getByName", String::class.java),
                inetClass.getDeclaredMethod("getAllByName", String::class.java)
            )) {
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val host = chain.getArg(0) as? String
                        if (host != null && blockAdNet && isAdHost(host)) {
                            if (dnsBlockedCount < 10) {
                                dnsBlockedCount++
                                ModuleLog.log("DNS拦截: $host")
                            }
                            return@intercept InetAddress.getByAddress(
                                host, byteArrayOf(0, 0, 0, 0)
                            )
                        }
                        chain.proceed()
                    }
            }
            ModuleLog.log("网络净化: DNS 广告域名拦截安装成功")
        } catch (t: Throwable) {
            ModuleLog.log("网络净化: DNS 拦截安装失败(降级): $t")
        }
    }

    // 调试：采集酷狗网关请求路径（去重，前40个）。hook 回调在 OkHttp 线程池
    // 多线程并发执行，LinkedHashSet 非线程安全，换并发集合
    private val debugUrls: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    private var callback: CredsCallback? = null

    /**
     * 凭证捕获回调
     */
    fun interface CredsCallback {
        fun onCredsCaptured(creds: KugouApi.Credentials)
    }

    /**
     * 设置凭证捕获回调
     */
    fun setCallback(cb: CredsCallback?) {
        callback = cb
    }

    // ===== 广告请求「假成功空响应」拦截（零闪现的关键） =====
    //
    // 逆向竞品模块（酷狗Bug）得到的架构差异：它 hook 的是**响应侧**
    // （OkHttp Callback.onResponse / ResponseBody），给广告 SDK 返回
    // **成功的空响应**；SDK 解析出"无广告"，压根不创建广告视图 —— 结构上
    // 不可能闪现，也不会留下空容器。
    // 而我们原方案是让请求**失败**（黑洞 URL / DNS→0.0.0.0）：SDK 收到的是
    // 错误，很多实现会照样 inflate 占位容器或走降级填充（发现页 ela
    // 槽位"空白变大"就是这个机理），只能靠视图层规则事后隐藏 → 必然有闪现窗口。
    // 因此改为：OkHttpClient.newCall() 直接返回一个合成的 Call，
    // execute()/enqueue() 都给出 200 + `{}` 的空 JSON —— 请求根本不发出网络。
    @Volatile
    private var adFakeInstalled = false
    @Volatile
    private var adFakeCount = 0

    /**
     * 构造 okhttp3.Response：200 + application/json 空对象
     */
    private fun buildEmptyResponse(classLoader: ClassLoader, request: Any): Any? = try {
        val reqCls = Class.forName("okhttp3.Request", false, classLoader)
        val builderCls = Class.forName("okhttp3.Response\$Builder", false, classLoader)
        val respBodyCls = Class.forName("okhttp3.ResponseBody", false, classLoader)
        val mediaCls = Class.forName("okhttp3.MediaType", false, classLoader)
        val protoCls = Class.forName("okhttp3.Protocol", false, classLoader)
        val http11 = protoCls.getField("HTTP_1_1").get(null)
        val mediaType = mediaCls.getMethod("parse", String::class.java)
            .invoke(null, "application/json; charset=utf-8")
        val body = respBodyCls.getMethod("create", mediaCls, String::class.java)
            .invoke(null, mediaType, "{}")
        val b = builderCls.getConstructor().newInstance()
        builderCls.getMethod("request", reqCls).invoke(b, request)
        builderCls.getMethod("protocol", protoCls).invoke(b, http11)
        builderCls.getMethod("code", Int::class.javaPrimitiveType).invoke(b, 200)
        builderCls.getMethod("message", String::class.java).invoke(b, "OK")
        builderCls.getMethod("body", respBodyCls).invoke(b, body)
        builderCls.getMethod("build").invoke(b)
    } catch (_: Throwable) {
        null
    }

    /**
     * 用动态代理合成 okhttp3.Call：不发起任何网络，直接给出既定响应。
     * 代理方式兼容 okhttp3/4（Call 是接口，方法集差异由 default 分支兜底）。
     */
    private fun makeFakeCall(
        callCls: Class<*>, respCls: Class<*>,
        request: Any, response: Any, timeoutObj: Any?
    ): Any? = try {
        val holder = arrayOfNulls<Any>(1)
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            try {
                when (method.name) {
                    "request" -> request
                    "execute" -> response
                    "enqueue" -> {
                        val cb = args?.firstOrNull()
                        if (cb != null) {
                            cb.javaClass.getMethod("onResponse", callCls, respCls)
                                .invoke(cb, holder[0], response)
                        }
                        null
                    }
                    "cancel" -> null
                    "isExecuted" -> java.lang.Boolean.TRUE
                    "isCanceled" -> java.lang.Boolean.FALSE
                    "timeout" -> timeoutObj
                    "clone" -> holder[0]
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            callCls.classLoader, arrayOf(callCls), handler
        )
        holder[0] = proxy
        proxy
    } catch (_: Throwable) {
        null
    }

    /**
     * 安装广告请求空响应拦截（hook OkHttpClient.newCall）
     */
    private fun installAdResponseBlock(xposed: XposedModule, classLoader: ClassLoader) {
        if (adFakeInstalled) return
        try {
            val clientCls = Class.forName("okhttp3.OkHttpClient", false, classLoader)
            val reqCls = Class.forName("okhttp3.Request", false, classLoader)
            val callCls = Class.forName("okhttp3.Call", false, classLoader)
            val respCls = Class.forName("okhttp3.Response", false, classLoader)
            val newCall = clientCls.getMethod("newCall", reqCls)
            val timeoutObj = try {
                Class.forName("okio.Timeout", false, classLoader)
                    .getConstructor().newInstance()
            } catch (_: Throwable) {
                null
            }
            xposed.hook(newCall)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!blockAdNet) return@intercept chain.proceed()
                    val request = chain.getArg(0) ?: return@intercept chain.proceed()
                    val url = try {
                        request.javaClass.getMethod("url").invoke(request)?.toString()
                    } catch (_: Throwable) {
                        null
                    }
                    if (url == null || !isBlockedAdHost(url)) return@intercept chain.proceed()
                    val resp = buildEmptyResponse(classLoader, request)
                    if (resp == null) return@intercept chain.proceed()
                    val fake = makeFakeCall(callCls, respCls, request, resp, timeoutObj)
                    if (fake == null) return@intercept chain.proceed()
                    if (adFakeCount < 10) {
                        adFakeCount++
                        ModuleLog.log("广告空响应拦截(零闪现): ${url.take(90)}")
                    }
                    fake
                }
            adFakeInstalled = true
            ModuleLog.log("网络净化: 广告请求空响应拦截安装成功")
        } catch (t: Throwable) {
            ModuleLog.log("网络净化: 广告空响应拦截安装失败(降级黑洞): $t")
        }
    }

    /**
     * 临时取证：dump GDT SDK 网络层（com.qq.e.comm.net.a.a.c.*）的类结构。
     * 目标：找到可以注入「成功空响应」的返回点（方法签名 + 返回类型）。
     */
    fun dumpGdtNetLayer(classLoader: ClassLoader, names: List<String>) {
        for (name in names) {
            try {
                val cls = Class.forName(name, false, classLoader)
                val sb = StringBuilder()
                sb.append("GDT网络层[${name}] 父类=${cls.superclass?.name} 接口=${cls.interfaces.joinToString { it.name }}\n")
                cls.declaredFields.forEach { f ->
                    sb.append("   字段 ${f.type.simpleName} ${f.name}\n")
                }
                cls.declaredMethods.forEach { m ->
                    sb.append(
                        "   方法 ${m.returnType.simpleName} ${m.name}(" +
                            m.parameterTypes.joinToString { it.simpleName } + ")\n"
                    )
                }
                ModuleLog.log(sb.toString())
            } catch (t: Throwable) {
                ModuleLog.log("GDT网络层[${name}] 取不到: $t")
            }
        }
    }

    /**
     * 安装 OkHttp Hook
     *
     * @param xposed      模块实例，用于安装 Hook
     * @param classLoader 酷狗概念版的 ClassLoader
     */
    fun install(xposed: XposedModule, classLoader: ClassLoader) {
        if (installed) return

        try {
            val builderClass = Class.forName(OKHTTP_BUILDER, false, classLoader)
            val build = findMethod(builderClass, "build")
            if (build == null) {
                ModuleLog.log("OkHttp Hook 安装失败: 未找到 build 方法")
                return
            }
            xposed.hook(build)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val request = chain.proceed()
                    try {
                        if (request == null) return@intercept null

                        // 获取 URL
                        val urlObj = request.callMethod("url") ?: return@intercept request
                        val urlStr = urlObj.toString()

                        // 广告请求网络层拦截：优先由 newCall 空响应拦截处理（零闪现）；
                        // 该通道不可用时才降级为改写黑洞（请求失败）
                        if (blockAdNet && !adFakeInstalled && isBlockedAdHost(urlStr)) {
                            val blocked = rewriteToBlackhole(request)
                            if (blocked != null) {
                                if (adBlockedCount < 10) {
                                    adBlockedCount++
                                    ModuleLog.log("广告拦截: ${urlStr.take(100)}")
                                }
                                return@intercept blocked
                            }
                        }

                        // 只处理酷狗域名请求
                        if (!urlStr.contains("kugou.com")) return@intercept request

                        // 调试：采集酷狗网关请求路径（去重前 200 个；40 不够覆盖
                        // feed 分页等后续接口，排障时尾部缺失）
                        if (debugUrls.size < 200 && debugUrls.add(urlStr.substringBefore("?"))) {
                            ModuleLog.log("净网调试: ${urlStr.substringBefore("?")}")
                        }

                        // 从 URL query 提取凭证
                        var token = urlObj.queryParam("token")
                        var userid = urlObj.queryParam("userid")
                        var dfid = urlObj.queryParam("dfid")
                        var mid = urlObj.queryParam("mid")
                        val appid = urlObj.queryParam("appid")
                        val clientver = urlObj.queryParam("clientver")

                        // 如果 URL 中没有，尝试从请求头获取
                        if (token.isNullOrEmpty() || userid.isNullOrEmpty()) {
                            try {
                                val headers = request.callMethod("headers")
                                if (headers != null) {
                                    if (token == null) token = headers.getHeader("token")
                                    if (userid == null) userid = headers.getHeader("userid")
                                    if (dfid == null) dfid = headers.getHeader("dfid")
                                    if (mid == null) mid = headers.getHeader("mid")
                                }
                            } catch (_: Exception) {
                            }
                        }

                        // 需要至少有 token 和 userid
                        if (token.isNullOrEmpty() || userid.isNullOrEmpty()) return@intercept request

                        // 去重：如果 token 没有变化则跳过
                        if (token == lastToken) return@intercept request
                        lastToken = token

                        val creds = KugouApi.Credentials().apply {
                            this.token = token
                            this.userid = userid
                            this.dfid = dfid
                            this.mid = mid
                            this.appid = appid
                            this.clientver = clientver
                        }

                        ModuleLog.log(
                            "捕获到凭证: userid=$userid" +
                                " dfid=${dfid?.let { it.take(8) + "..." } ?: "null"}" +
                                " mid=${if (mid != null) "yes" else "null"}"
                        )

                        callback?.onCredsCaptured(creds)
                    } catch (_: Exception) {
                        // 静默处理，避免影响酷狗正常运行
                    }
                    request
                }

            installed = true
            ModuleLog.log("OkHttp Hook 安装成功")
        } catch (e: Exception) {
            ModuleLog.log("OkHttp Hook 安装失败（酷狗可能未使用标准OkHttp）: $e")
        }

        // 广告请求空响应拦截（零闪现主通道，必须在 OkHttp Hook 之后安装）
        installAdResponseBlock(xposed, classLoader)

        // DNS 层广告域名拦截（独立于 OkHttp Hook，覆盖非 OkHttp 网络库）
        installDnsBlock(xposed)
    }

    /**
     * 反射调用无参方法（Method 安装期已缓存，热路径不再 getMethod 遍历方法表）
     */
    private fun Any?.callMethod(name: String): Any? = try {
        cachedMethod(this!!.javaClass, name)?.invoke(this)
    } catch (_: Exception) {
        null
    }

    /**
     * 从 okhttp3.HttpUrl 提取 query 参数
     */
    private fun Any?.queryParam(key: String): String? = try {
        cachedMethod(this!!.javaClass, "queryParameter", String::class.java)
            ?.invoke(this, key)?.toString()
    } catch (_: Exception) {
        null
    }

    /**
     * 从 okhttp3.Headers 提取 header 值
     */
    private fun Any?.getHeader(name: String): String? = try {
        cachedMethod(this!!.javaClass, "get", String::class.java)
            ?.invoke(this, name)?.toString()
    } catch (_: Exception) {
        null
    }

    // Method 缓存：OkHttp 每次 build 都会走到这几个反射点，
    // getMethod 每次遍历方法表，酷狗一次浏览数百请求，必须缓存
    private val methodCache =
        java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method?>()

    private fun cachedMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? =
        methodCache.getOrPut("$clazz.name#$name#${paramTypes.joinToString(",") { it.name }}") {
            try {
                clazz.getDeclaredMethod(name, *paramTypes)
            } catch (_: NoSuchMethodException) {
                try {
                    clazz.getMethod(name, *paramTypes)
                } catch (_: NoSuchMethodException) {
                    null
                }
            }
        }

    /**
     * 查找方法（先声明方法后公共方法），找不到返回 null
     */
    private fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? =
        cachedMethod(clazz, name, *paramTypes)
}
