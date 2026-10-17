package com.bldf1.kugoulite.plus

import android.content.Context

/**
 * 领取编排器
 *
 * 工作流程：
 * 1. 酷狗概念版 Application.onCreate 时启动
 * 2. 尝试从缓存 / 酷狗 SP 读取已有凭证，同时 OkHttp Hook 捕获凭证
 * 3. 凭证就绪后检查配置与今日状态，执行领取
 * 4. 领取成功后可选升级概念版VIP
 * 5. 结果写入设置面板的状态显示，并发送通知
 */
object ClaimWorker {

    @Volatile
    private var started = false

    // 原子领取锁：NetCapture 回调线程与 start/forceClaim 可能并发触发，
    // check-then-act 的布尔标志存在双发请求竞态，compareAndSet 消除
    private val attempting = java.util.concurrent.atomic.AtomicBoolean(false)

    private lateinit var appContext: Context

    // OkHttp 回调线程写、领取线程读，@Volatile 保证发布可见
    @Volatile
    private var cachedCreds: KugouApi.Credentials? = null

    // 网络类失败重试计数（仅网络故障重试，风控/参数错误不重试）
    private const val MAX_NET_RETRY = 3
    private const val NET_RETRY_DELAY_MS = 60_000L
    private var netRetryCount = 0

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 启动领取工作器（由 MainHook 在 Application.onCreate 后调用）
     */
    private const val MAIN_PROCESS = "com.kugou.android.lite"

    fun start(context: Context, classLoader: ClassLoader) {
        if (started) return

        // 只在主进程执行领取逻辑（support/message 等子进程直接跳过，
        // 避免多进程重复请求广告/领取接口）
        val processName = try {
            // getProcessName 是 API 28+；27 及以下退回 applicationInfo.processName
            if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else context.applicationInfo.processName ?: ""
        } catch (_: Throwable) {
            context.applicationInfo.processName ?: ""
        }
        if (processName != MAIN_PROCESS) {
            ModuleLog.log("子进程($processName)跳过领取逻辑")
            return
        }

        started = true
        appContext = context.applicationContext

        ModuleLog.log("ClaimWorker 启动")

        // 同步广告请求拦截开关
        NetCapture.setBlockAdNet(Settings.isBlockAdNet(appContext))
        NetCapture.setBlockTaskPromo(Settings.isBlockTaskPromo(appContext))

        // 防检测自检：验证签名 Hook 拦截与缓存一致性
        IntegrityBypass.selfTest(appContext)

        // 设置 NetCapture 回调
        NetCapture.setCallback { creds -> onCredsCaptured(creds) }

        // 尝试从缓存读取凭证
        cachedCreds = PrefStore.loadCreds(appContext)
        if (cachedCreds?.isValid == true) {
            ModuleLog.log("使用缓存凭证: userid=${cachedCreds?.userid}")
            attemptClaim()
            return
        }

        // 缓存没有，后台从酷狗 SP 读取（遍历全 SP 目录是重 IO，
        // Application.onCreate 主线程阻塞会拖慢冷启动）
        Thread({
            val fromPrefs = PrefStore.tryReadFromAppPrefs(appContext)
            if (fromPrefs?.isValid == true && cachedCreds?.isValid != true) {
                ModuleLog.log("使用酷狗SP凭证: userid=${fromPrefs.userid}")
                PrefStore.saveCreds(appContext, fromPrefs)
                cachedCreds = fromPrefs
                attemptClaim()
            } else {
                ModuleLog.log("暂无凭证，等待 OkHttp 捕获...")
            }
        }, "KuGouLiteVip-PrefScan").start()
    }

    /**
     * NetCapture 捕获到凭证时回调
     */
    private fun onCredsCaptured(creds: KugouApi.Credentials) {
        ModuleLog.log("NetCapture 捕获到凭证: userid=${creds.userid}")
        PrefStore.saveCreds(appContext, creds)
        cachedCreds = creds
        attemptClaim()
    }

    /**
     * 手动触发重新领取（设置面板「立即重新领取」按钮，同进程直调）
     * 强制执行：重置今日状态并跳过去重检查
     */
    fun forceClaim(ctx: Context) {
        ModuleLog.log("手动触发重新领取")
        PrefStore.setClaimedToday(ctx, false)
        PrefStore.setClaimedSuccessToday(ctx, false)
        if (cachedCreds?.isValid != true) {
            cachedCreds = PrefStore.loadCreds(ctx) ?: PrefStore.tryReadFromAppPrefs(ctx)
        }
        if (cachedCreds?.isValid == true) {
            attemptClaim(force = true)
        } else {
            Settings.putStatus(ctx, "无可用凭证，请先登录酷狗", KugouApi.CODE_ERROR)
        }
    }

    /**
     * 执行领取（如果条件满足）
     *
     * @param force 手动触发时为 true：跳过「今日已领取」检查，直接请求接口
     */
    private fun attemptClaim(force: Boolean = false) {
        if (!attempting.compareAndSet(false, true)) {
            ModuleLog.log("已有领取任务进行中，跳过")
            return
        }
        val creds = cachedCreds
        if (creds == null || !creds.isValid) {
            attempting.set(false)
            ModuleLog.log("凭证无效，跳过领取")
            return
        }

        // 检查是否启用
        val enabled = Settings.isEnabled(appContext)
        ModuleLog.log("模块启用状态: $enabled")
        if (!enabled) {
            attempting.set(false)
            ModuleLog.log("模块已禁用")
            Settings.putStatus(appContext, "模块已禁用", KugouApi.CODE_ERROR)
            return
        }

        // 检查今天是否已成功领取（手动触发强制跳过该检查）
        val localDay = KugouApi.localDayKey
        val claimedSuccess = PrefStore.isClaimedSuccessToday(appContext)
        ModuleLog.log("日期检查: 本地=$localDay 今日已成功领取=$claimedSuccess force=$force")
        if (claimedSuccess && !force) {
            attempting.set(false)
            ModuleLog.log("今日已成功领取，跳过")
            Settings.putStatus(appContext, "今日已领取", KugouApi.CODE_ALREADY)
            return
        }

        ModuleLog.log("凭证有效，开始领取: userid=${creds.userid}")
        doClaimThread(creds, force)
    }

    private fun doClaimThread(creds: KugouApi.Credentials, force: Boolean) {

        // 在子线程执行网络请求
        Thread({
            try {
                ModuleLog.log("开始领取每日VIP...")

                // 第零步：听歌任务打卡（听歌领VIP的任务前提，失败不阻断领取）
                if (Settings.isFeature(appContext, Settings.KEY_LISTEN_TASK)) {
                    ListenTask.report(appContext, creds)
                }

                // 第一步：领取听歌VIP
                val result = KugouApi.claimVip(creds)
                ModuleLog.log("领取结果: code=${result.code} msg=${result.message}")

                // 只有成功或今日已领取才标记为今日已成功领取
                if (result.code == KugouApi.CODE_SUCCESS || result.code == KugouApi.CODE_ALREADY) {
                    PrefStore.setClaimedSuccessToday(appContext, true)
                    PrefStore.setClaimedToday(appContext, true)
                }

                // 第二步：如果领取成功或今日已领取，且开启自动升级，则升级概念版VIP
                // code=0(成功) 或 code=1(今日已领取) 都尝试升级
                if (result.code == KugouApi.CODE_SUCCESS || result.code == KugouApi.CODE_ALREADY) {
                    val autoUpgrade = Settings.isAutoUpgrade(appContext)
                    ModuleLog.log("自动升级开关: $autoUpgrade")
                    if (autoUpgrade) {
                        ModuleLog.log("开始升级概念版VIP...")
                        Thread.sleep(500)
                        val upgradeResult = KugouApi.upgradeVip(creds)
                        ModuleLog.log("升级结果: code=${upgradeResult.code} msg=${upgradeResult.message}")
                        if (upgradeResult.code == KugouApi.CODE_SUCCESS) {
                            result.message += "，已升级概念版VIP"
                        } else if (upgradeResult.code == KugouApi.CODE_ALREADY) {
                            result.message += "，概念版VIP今日已升级"
                        }
                    }
                }

                // 回写状态
                Settings.putStatus(appContext, result.message, result.code)

                // 网络类失败重试：当天若用户不再打开酷狗就永远漏领，
                // 网络抖动失败（无响应/请求异常）延迟后自动补试；
                // 风控/参数错误/已领取不重试
                if (result.code == KugouApi.CODE_ERROR
                    && result.message?.contains("网络") == true
                    && !force
                    && netRetryCount < MAX_NET_RETRY - 1
                ) {
                    netRetryCount++
                    ModuleLog.log("网络失败，${NET_RETRY_DELAY_MS / 1000}s 后重试(${netRetryCount}/$MAX_NET_RETRY)")
                    mainHandler.postDelayed({ attemptClaim() }, NET_RETRY_DELAY_MS)
                } else {
                    netRetryCount = 0
                }
            } catch (e: Exception) {
                ModuleLog.log("领取过程异常: $e")
                Settings.putStatus(appContext, "领取异常: ${e.message}", KugouApi.CODE_ERROR)
            } finally {
                attempting.set(false)
            }
        }, "KuGouLiteVip-Claim").start()
    }
}
