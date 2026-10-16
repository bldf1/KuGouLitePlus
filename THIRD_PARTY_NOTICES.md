# 第三方组件声明（THIRD PARTY NOTICES）

本模块包含/引用以下第三方开源项目，感谢原作者。除特别说明外，本项目未修改这些组件的源码；  
对源码级移植的部分，对应文件头部亦保留了原版权声明。

## 1. Lyricon（krckit 歌词内核）

- **来源**：<https://github.com/proify/lyricon> （io.github.proify.lyricon）
- **版权**：Copyright 2026 Proify, Tomakino
- **许可**：Apache License 2.0
- **使用方式**：
  - `io.github.proify.lyricon:provider:0.1.70`（Gradle 依赖，随 APK 分发）
  - `KrcDecryptor.kt` / `KrcParser.kt` 为 krckit 的 Kotlin 移植改写  
    （去掉 kotlinx.serialization，改用 org.json），文件头保留原声明

## 2. AndroidLiquidGlassView（液态玻璃）

- **来源**：<https://github.com/QmDeve/AndroidLiquidGlassView>
- **版权**：Copyright (c) 2025-2026 Donny Yang (QmDeve)
- **许可**：MIT License
- **使用方式**：`glass/GlassShader.kt` 等着色器代码逐字移植（参数有本地调优），文件头保留原声明

## 3. libxposed API

- **来源**：<https://github.com/libXposed/api> （io.github.libxposed:api:102.0.0）
- **版权**：Copyright (c) LSPosed 团队
- **许可**：Apache License 2.0
- **使用方式**：compileOnly 编译期接口，运行时由 LSPosed 框架提供，不随 APK 分发

## 4. Kotlin Coroutines

- **来源**：<https://github.com/Kotlin/kotlinx.coroutines>
- **版权**：Copyright 2016-2021 JetBrains s.r.o
- **许可**：Apache License 2.0
- **使用方式**：Gradle 依赖，随 APK 分发

## 5. 参考项目（非代码引用）

- **MakcRe/KuGouMusicApi** — 自动领取接口参考  
  （<https://github.com/MakcRe/KuGouMusicApi> ，`KugouApi.kt`/`ListenTask.kt`  
  注释中注明参考其 youth_listen_song 实现；为本次开发中重新引用）

---

## 许可证要点

除 AndroidLiquidGlassView 为 MIT License 外，其余组件均基于 Apache License 2.0 授权。依据各许可证，本项目的分发已：

1. 在移植的源文件头部保留原版权声明与许可说明；
2. 在本文件中列明各组件的版权与许可信息。

完整许可证文本见各上游仓库的 LICENSE 文件；  
Apache License 2.0：<http://www.apache.org/licenses/LICENSE-2.0>  
MIT License：<https://github.com/QmDeve/AndroidLiquidGlassView/blob/master/LICENSE>
