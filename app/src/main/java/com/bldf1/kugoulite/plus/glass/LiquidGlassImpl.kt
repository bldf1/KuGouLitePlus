package com.bldf1.kugoulite.plus.glass

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.SystemClock
import android.view.View

/**
 * 液态玻璃渲染实现（Round 28）
 *
 * 移植自 **QmDeve/AndroidLiquidGlassView**（MIT，Copyright (c) 2025-2026 Donny Yang）的
 * `com.qmdeve.liquidglass.impl.LiquidGlassimpl`，关键改动见 [record]。
 *
 * ## 原理（零 Bitmap 分配）
 * `RenderNode` 录下**宿主背后那块屏幕内容** → 挂 `RenderEffect`（AGSL RuntimeShader，
 * `uniform shader content` 即录到的背景）→ 宿主 `onDraw` 里 `canvas.drawRenderNode(node)`。
 * 每帧由目标视图的 `OnPreDrawListener` 触发重录 ⇒ 背景是**实时**的（页面滚动时玻璃跟着流动）。
 *
 * ⭐ **与原库唯一的实质性差异：`record()` 的录屏尺寸改用 host 尺寸。**
 * 原库 `beginRecording(target.w, target.h)` 而 `node.setPosition(0,0,host.w,host.h)` ——
 * 它 demo 里 host 与 target 同为全屏所以看不出问题；本项目 host=胶囊(1124x186)、
 * target=内容根(1220x2712)，两者尺寸不同，录屏边界与节点摆放矩形不一致 ⇒
 * 内容会被整体缩放/错位。改成 host 尺寸后两者严格一致，缩放恒为 1，无歧义
 * （`translate` 的对齐方向原库本来就是对的：`target 屏幕位置 - host 屏幕位置`）。
 */
@SuppressLint("NewApi")
internal class LiquidGlassImpl(
    private val host: View,
    private val target: View,
    private val config: GlassConfig,
) {

    private val node = RenderNode("KuGouLiquidGlass")
    private val tp = IntArray(2)
    private val hp = IntArray(2)
    private val liquidShader: RuntimeShader = RuntimeShader(GlassShader.SOURCE)

    private var cachedBlurEffect: RenderEffect? = null
    private var lastSigma = Float.NaN
    private var lastBlurUpdateTime = 0L
    private var lastRecordAt = 0L
    private var needsUpdate = true

    // 参数变更检测（避免每帧都重建 RenderEffect 链 —— 那是每帧最贵的一环）
    private var lastCornerRadius = Float.NaN
    private var lastCornerRadii: FloatArray? = null
    private var lastRefractionHeight = Float.NaN
    private var lastRefractionAmount = Float.NaN
    private var lastContrast = Float.NaN
    private var lastWhitePoint = Float.NaN
    private var lastChromaMultiplier = Float.NaN
    private var lastBlurLevel = Float.NaN
    private var lastChromaticAberration = Float.NaN
    private var lastDepthEffect = Float.NaN
    private var lastTintRed = Float.NaN
    private var lastTintGreen = Float.NaN
    private var lastTintBlue = Float.NaN
    private var lastTintAlpha = Float.NaN

    init {
        // 首次布局前尺寸为 0，等 onSizeChanged 再上效果
        host.post { applyRenderEffect() }
    }

    fun onSizeChanged(w: Int, h: Int) {
        config.width = w
        config.height = h
        node.setPosition(0, 0, w, h)
        record()
        applyRenderEffect()
    }

    fun onPreDraw() {
        // 节流（本项目自加，默认 0=每帧）：背景重录 = 整棵内容树重绘，掉帧时的第一旋钮
        val interval = config.recordIntervalMs
        if (interval > 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRecordAt < interval) return
            lastRecordAt = now
        }
        record()

        val cornerRadius = config.cornerRadiusPx
        // 四角半径（TL/TR/BR/BL）：给全了就用它，否则退回「四角同值」
        val cornerRadii = config.cornerRadii?.takeIf { it.size == 4 }
            ?: floatArrayOf(cornerRadius, cornerRadius, cornerRadius, cornerRadius)
        val refractionHeight = config.refractionHeight
        val refractionAmount = config.refractionOffset
        val contrast = config.contrast
        val whitePoint = config.whitePoint
        val chromaMultiplier = config.chromaMultiplier
        val blurLevel = config.blurRadius
        val chromaticAberration = config.dispersion
        val depthEffect = config.depthEffect
        val tintRed = config.tintColorRed
        val tintGreen = config.tintColorGreen
        val tintBlue = config.tintColorBlue
        val tintAlpha = config.tintAlpha

        val changed = lastCornerRadius != cornerRadius ||
            lastCornerRadii?.contentEquals(cornerRadii) != true ||
            lastRefractionHeight != refractionHeight ||
            lastRefractionAmount != refractionAmount ||
            lastContrast != contrast ||
            lastWhitePoint != whitePoint ||
            lastChromaMultiplier != chromaMultiplier ||
            lastBlurLevel != blurLevel ||
            lastChromaticAberration != chromaticAberration ||
            lastDepthEffect != depthEffect ||
            lastTintRed != tintRed ||
            lastTintGreen != tintGreen ||
            lastTintBlue != tintBlue ||
            lastTintAlpha != tintAlpha ||
            needsUpdate

        if (changed) {
            lastCornerRadius = cornerRadius
            lastCornerRadii = cornerRadii.copyOf()
            lastRefractionHeight = refractionHeight
            lastRefractionAmount = refractionAmount
            lastContrast = contrast
            lastWhitePoint = whitePoint
            lastChromaMultiplier = chromaMultiplier
            lastBlurLevel = blurLevel
            lastChromaticAberration = chromaticAberration
            lastDepthEffect = depthEffect
            lastTintRed = tintRed
            lastTintGreen = tintGreen
            lastTintBlue = tintBlue
            lastTintAlpha = tintAlpha
            needsUpdate = false
            applyRenderEffect()
        }
    }

    /**
     * 把 target 子树录进 RenderNode（坐标系 = host 的本地坐标系）。
     *
     * ⭐ 用 host 尺寸开录（见类注释）：录屏边界 == `node.setPosition` 矩形 ⇒ 无缩放。
     * `translate` 把 target 的屏幕位置搬到 host 的屏幕位置 —— 之后 rec 的 (0,0)
     * 就精确对应屏幕上 host 的左上角，着色器的 `coord` 因此与胶囊本地坐标一一对应。
     */
    private fun record() {
        val w = host.width
        val h = host.height
        if (w == 0 || h == 0) return

        val rec = node.beginRecording(w, h)
        // 显式裁剪到节点矩形：录进来的树是整屏 1220x2712 的内容根，而我们只要
        // 1124x186 这一条 —— 有 clip 之后落在条外的 draw op 能在重放时被剔除。
        rec.clipRect(0, 0, w, h)
        target.getLocationInWindow(tp)
        host.getLocationInWindow(hp)
        rec.translate((tp[0] - hp[0]).toFloat(), (tp[1] - hp[1]).toFloat())
        target.draw(rec)
        node.endRecording()
    }

    fun draw(canvas: Canvas) {
        if (!canvas.isHardwareAccelerated()) return
        canvas.drawRenderNode(node)
    }

    private fun applyRenderEffect() {
        val width = host.width
        val height = host.height
        if (width == 0 || height == 0) return
        // 尺寸以 host 为准（原库取 target 尺寸；host≠target 时着色器的 halfSize 会算错）
        config.width = width
        config.height = height

        val cornerRadiusPx = config.cornerRadiusPx
        val refractionHeight = config.refractionHeight
        val refractionAmount = config.refractionOffset
        val contrast = config.contrast
        val whitePoint = config.whitePoint
        val chromaMultiplier = config.chromaMultiplier
        val blurLevel = if (config.blurRadius > 0f) config.blurRadius else 0f
        val chromaticAberration = config.dispersion
        val depthEffect = config.depthEffect
        val tintRed = config.tintColorRed
        val tintGreen = config.tintColorGreen
        val tintBlue = config.tintColorBlue
        val tintAlpha = config.tintAlpha

        val size = floatArrayOf(width.toFloat(), height.toFloat())
        val offset = floatArrayOf(0f, 0f)
        // 四角半径（TL/TR/BR/BL）：`cornerRadii` 给了就用它（底部弹层 = [r,r,0,0]），
        // 否则退回「四角同值」的旧行为（胶囊 = 高度/2）
        val cornerRadii = config.cornerRadii?.takeIf { it.size == 4 }
            ?: floatArrayOf(cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx)

        var contentEffect: RenderEffect? = null
        if (blurLevel > 0.01f) {
            val now = System.currentTimeMillis()
            if (cachedBlurEffect == null || Math.abs(blurLevel - lastSigma) > 0.3f ||
                now - lastBlurUpdateTime > 120
            ) {
                contentEffect = try {
                    RenderEffect.createBlurEffect(blurLevel, blurLevel, Shader.TileMode.CLAMP)
                        .also {
                            cachedBlurEffect = it
                            lastSigma = blurLevel
                            lastBlurUpdateTime = now
                        }
                } catch (_: Throwable) {
                    cachedBlurEffect
                }
            } else {
                contentEffect = cachedBlurEffect
            }
        }

        liquidShader.setFloatUniform("size", size)
        liquidShader.setFloatUniform("offset", offset)
        liquidShader.setFloatUniform("cornerRadii", cornerRadii)
        liquidShader.setFloatUniform("refractionHeight", refractionHeight)
        liquidShader.setFloatUniform("refractionAmount", refractionAmount)
        liquidShader.setFloatUniform("depthEffect", depthEffect)
        liquidShader.setFloatUniform("chromaticAberration", chromaticAberration)
        liquidShader.setFloatUniform("contrast", contrast)
        liquidShader.setFloatUniform("whitePoint", whitePoint)
        liquidShader.setFloatUniform("chromaMultiplier", chromaMultiplier)
        liquidShader.setFloatUniform("tintColor", floatArrayOf(tintRed, tintGreen, tintBlue))
        liquidShader.setFloatUniform("tintAlpha", tintAlpha)

        val shaderEffect = RenderEffect.createRuntimeShaderEffect(liquidShader, "content")
        // 链式：blur 先作用于录到的背景，再喂给着色器（`content` = 已模糊的背景）
        node.setRenderEffect(
            if (contentEffect != null) {
                RenderEffect.createChainEffect(shaderEffect, contentEffect)
            } else {
                shaderEffect
            },
        )
    }

    /** RenderNode / RuntimeShader 随 GC 释放，无需显式清理；目标 VTO 监听由宿主视图摘除 */
    fun dispose() = Unit
}
