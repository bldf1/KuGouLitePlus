# 酷狗概念版+ (KuGouLitePlus)

LSPosed 模块：为酷狗概念版（`com.kugou.android.lite`）提供每日 VIP 自动领取、界面净化、悬浮底栏、词幕歌词提供等增强功能。

**模块无独立界面** — 安装并在 LSPosed 中启用后，在酷狗概念版内**长按底栏超过 0.8 秒**呼出设置面板，全部开关即改即生效，无需重启。

## 功能

### 自动领取 VIP

- 启动酷狗概念版时自动调用官方接口领取每日听歌会员，不依赖弹窗
- 拦截含「领取/签到」关键词的领取弹窗，弹窗不再出现
- 领取成功后可选自动升级至概念版 VIP
- Hook 酷狗 OkHttp 请求自动捕获登录凭证，按天去重，避免重复请求
- 通过拦截广告域名请求减少无关网络流量（可独立开关）

### 界面净化（34 个独立开关，每个控件一个）

- **发现页**：开屏推广、Banner 广告、推广卡片、淘宝推广条、天猫活动卡、Banner 空槽位等
- **播放页**：播放页广告、听书推广、每周福利入口、投稿提示、「咱一起自律」入口等
- **搜索页**：搜索广告、活动 Banner、关闭提示、VIP 推广条等
- **我的页**：签到条、会员徽标、VIP 到期提示、赚钱入口、勋章墙等
- **其他**：概念实验室入口、登录引导、免费听歌入口/角标/悬浮块、我的页底部栏、顶部搜索、TAB 角标、频道入口等

净化引擎采用挂载期预隐藏 + 多帧复核 + 复活压制策略，做到**零闪现**；规则内置区域门禁与祖先 ID 白名单，最大限度避免同 ID 跨页复用导致的误伤。

### 悬浮底栏

- 将酷狗概念版底栏改为带液态玻璃效果的胶囊形态，只修改尺寸与轮廓、不重建 tab
- 搜索页自动隐藏；宿主重排时窗口级同帧修正（带熔断保护）
- 支持关闭玻璃效果，自动回退为实色兜底

### 词幕歌词提供

- 将酷狗本地 `.krc`（逐字 + 翻译/罗马音）解密解析后，通过 Lyricon 的 `IProviderBinder` 提供给运行在 SystemUI 中的词幕中心服务
- KRC 解密与解析逻辑移植自 [Lyricon](https://github.com/proify/lyricon) 的 krckit

### 防检测

- 降低模块在宿主进程中的可见特征，减少被宿主检测的风险

## 工作原理（自动领取链路）

```
酷狗概念版启动
    │
    ▼
MainHook.handleLoadPackage()
    │
    ├── PopupBlocker.install()       ← 拦截 Dialog.show / WindowManager.addView
    │                                  含"领取/签到"关键词的弹窗直接阻止
    ├── NetCapture.install()         ← Hook OkHttp Request.Builder.build()
    │                                  捕获 kugou.com 请求中的 token/userid/dfid/mid
    └── Hook Application.onCreate()
            │
            ▼
      ClaimWorker.start()
            │
            ├── 检查模块开关（直读落盘 XML，跨进程可见）
            ├── 检查今日是否已领取（北京时间）
            ├── 等待 OkHttp 捕获凭证
            │
            ▼
      KugouApi.claimVip()             ← POST /youth/v1/recharge/receive_vip_listen_song
            │
            ▼  (code=0 或 1)
      KugouApi.upgradeVip()           ← POST /youth/v1/listen_song/upgrade_vip_reward
```

## 测试与验证

三条核心判据（真闪现 / 顶栏背景 / 底栏胶囊）、回归怎么跑、以及两个真实反例见
[docs/TESTING.md](docs/TESTING.md)。

## 更新日志

各版本变更见 [CHANGELOG.md](CHANGELOG.md)。

## 构建

```bash
./gradlew :app:assembleRelease
```

- 依赖：Android SDK、JDK 21
- Release 构建启用 R8 混淆与资源收缩，APK 体积约 0.5 MB
- 仅依赖 `io.github.libxposed:api`（compileOnly）与 kotlinx-coroutines，零第三方 UI 库（设置面板为纯原生 View 实现）

## 安装

1. 设备需已安装 [LSPosed](https://github.com/LSPosed/LSPosed)（或兼容 libxposed API 的 Xposed 实现）
2. 安装本模块 APK
3. 在 LSPosed 管理器中启用模块，作用域勾选**酷狗概念版**
4. 强制停止并重新打开酷狗概念版
5. 在酷狗概念版内**长按底栏**呼出设置面板，按需开启功能

### 没有 root？用 LSPatch

设备没有 root 时，可以用 [LSPatch](https://github.com/JingMatrix/LSPatch) 把模块挂到酷狗概念版上（免 root、免 Zygisk）：

1. 安装 LSPatch 管理器（`manager.apk`）
2. 在管理器里选择**酷狗概念版**作为目标应用，勾选本模块，生成补丁包并安装
3. 用**打好的酷狗概念版**启动（不是原版），长按底栏呼出面板

几点说明：

- LSPatch 要求 **Android 9（API 28）及以上**（本模块本身支持 8.1，但这条路径从 9 起步）
- 补丁过程会重打包并替换签名，LSPatch 自带签名绕过；若仍提示"签名异常"，请确认打补丁时签名绕过选项已开启
- Manager 模式下，之后增删模块无需重新打补丁；Integrated 模式会把模块固化进 APK，体积更小但改动需重打
- 与 root + LSPosed 相比，LSPatch 只影响被补丁的那一个应用，依赖系统级能力的开关会失效

## 兼容性

- **Android 8.1（API 27）及以上**；LSPosed / libxposed API 102（targetSdk 34）

- 目标应用：酷狗概念版 v5.2.9（其他版本未经充分测试，规则 ID 可能失效）
- 已在 HyperOS（Android 16）真机验证；不同分辨率/系统下区域门禁阈值可能需要微调

## 免责声明

本项目仅供学习与研究，请于 24 小时内自行删除。使用本项目产生的任何后果由使用者自行承担，与作者无关。请支持正版音乐服务。

## 致谢

- [MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi) — 自动领取接口的参数组织方式参考（Apache-2.0）
- [proify/lyricon](https://github.com/proify/lyricon) — KRC 歌词解密/解析（krckit）移植来源（Apache-2.0）
- [QmDeve/AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView) — 液态玻璃着色器移植来源（MIT）
- [libxposed/api](https://github.com/libxposed/api) — 模块构建所用的 Xposed API（Apache-2.0）

完整的第三方组件声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可证

[Apache License 2.0](LICENSE)
