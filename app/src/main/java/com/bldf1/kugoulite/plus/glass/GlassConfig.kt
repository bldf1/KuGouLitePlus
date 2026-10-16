package com.bldf1.kugoulite.plus.glass

/**
 * 液态玻璃参数（Round 28）
 *
 * 移植自 **QmDeve/AndroidLiquidGlassView**（MIT License，Copyright (c) 2025-2026 Donny Yang，
 * https://github.com/QmDeve/AndroidLiquidGlassView）的 `com.qmdeve.liquidglass.Config`：
 * 改为 Kotlin、去掉 `androidx.annotation` 依赖、删掉着色器里并不存在的 `ECCENTRIC_FACTOR`。
 *
 * ⚠️ 所有长度单位都是 **px**（不是 dp）—— 调用方自己按 density 换算。
 * 所有字段只在主线程读写（宿主 PreDraw 回调 + 设置面板回写都在主线程），故不加 volatile。
 */
internal class GlassConfig {

    /** 色散（RGB 分离）强度，0 = 关闭。典型 0.2~0.5 */
    var dispersion: Float = 0f

    /** 深度效果：边缘法线朝中心方向的额外权重，0~1 */
    var depthEffect: Float = 0.3f

    /** 玻璃尺寸（px）。由 [LiquidGlassImpl] 在 onSizeChanged 自动同步，调用方无需设置 */
    var width: Int = 0
    var height: Int = 0

    /** 圆角半径（px）。胶囊 = 高度/2。四角相同，等价于 [cornerRadii] 全填该值 */
    var cornerRadiusPx: Float = 0f

    /**
     * 四角半径（px），顺序 **TL / TR / BR / BL**；非空时**覆盖** [cornerRadiusPx]。
     *
     * Round 34 新增：底部弹层要「只圆上两角」⇒ 传 `[r, r, 0, 0]`。
     * 着色器本来就按象限取半径（`radiusAt`），原库只是把它写成了四角同值，
     * 所以这里只需把数组原样传下去，着色器不用改。
     */
    var cornerRadii: FloatArray? = null

    /** 折射带宽度（px）：从边缘向内多宽的范围内发生折射 */
    var refractionHeight: Float = 0f

    /** 折射位移（px）：负值 = 向内采样（边缘像凸透镜放大），正值 = 向外 */
    var refractionOffset: Float = 0f

    /** 对比度增益：rgb = (rgb-0.5)*(1+contrast)+0.5 */
    var contrast: Float = 0f

    /** 白点：rgb 朝白(+)或黑(-)混合的比例，绝对值即混合量 */
    var whitePoint: Float = 0f

    /** 彩度倍率，1 = 原样 */
    var chromaMultiplier: Float = 1f

    /** 背景模糊半径（px，即高斯 sigma），0 = 不模糊 */
    var blurRadius: Float = 0f

    /** 染色：tintColor(0~1) 按 tintAlpha 混入 */
    var tintAlpha: Float = 0f
    var tintColorRed: Float = 1f
    var tintColorGreen: Float = 1f
    var tintColorBlue: Float = 1f

    /**
     * 录屏（background capture）最小间隔 ms；0 = 每次 PreDraw 都重录。
     *
     * ⚠️ 本项目自加的节流阀（原库没有）：玻璃背景靠 `target.draw()` 重录整棵内容树，
     * 是最贵的一环。掉帧时把它调到 33（≈30fps）即可，无需改代码结构。
     */
    var recordIntervalMs: Long = 0

    fun configure(overrides: Overrides?) {
        overrides?.applyTo(this)
    }

    /** 把另一份配置的非尺寸字段拷进来（复用已挂载的玻璃层时刷新深浅色等参数） */
    fun copyFrom(o: GlassConfig) {
        dispersion = o.dispersion
        depthEffect = o.depthEffect
        cornerRadiusPx = o.cornerRadiusPx
        cornerRadii = o.cornerRadii?.copyOf()
        refractionHeight = o.refractionHeight
        refractionOffset = o.refractionOffset
        contrast = o.contrast
        whitePoint = o.whitePoint
        chromaMultiplier = o.chromaMultiplier
        blurRadius = o.blurRadius
        tintAlpha = o.tintAlpha
        tintColorRed = o.tintColorRed
        tintColorGreen = o.tintColorGreen
        tintColorBlue = o.tintColorBlue
        recordIntervalMs = o.recordIntervalMs
    }

    /** 链式覆盖构建器（与原库 Overrides 对齐，便于深浅色两套参数切换） */
    class Overrides {
        private var cornerRadius: Float? = null
        private var cornerRadii: FloatArray? = null
        private var refractionHeight: Float? = null
        private var refractionOffset: Float? = null
        private var contrast: Float? = null
        private var whitePoint: Float? = null
        private var chromaMultiplier: Float? = null
        private var blurRadius: Float? = null
        private var dispersion: Float? = null
        private var tintAlpha: Float? = null
        private var tintColorRed: Float? = null
        private var tintColorGreen: Float? = null
        private var tintColorBlue: Float? = null
        private var recordIntervalMs: Long? = null

        fun cornerRadius(v: Float) = apply { cornerRadius = v }

        /** 四角半径（px），顺序 TL / TR / BR / BL；传了就覆盖 [cornerRadius] */
        fun cornerRadii(tl: Float, tr: Float, br: Float, bl: Float) =
            apply { cornerRadii = floatArrayOf(tl, tr, br, bl) }
        fun refractionHeight(v: Float) = apply { refractionHeight = v }
        fun refractionOffset(v: Float) = apply { refractionOffset = v }
        fun contrast(v: Float) = apply { contrast = v }
        fun whitePoint(v: Float) = apply { whitePoint = v }
        fun chromaMultiplier(v: Float) = apply { chromaMultiplier = v }
        fun blurRadius(v: Float) = apply { blurRadius = v }
        fun dispersion(v: Float) = apply { dispersion = v }
        fun tintAlpha(v: Float) = apply { tintAlpha = v }
        fun recordIntervalMs(v: Long) = apply { recordIntervalMs = v }

        fun tintColor(r: Float, g: Float, b: Float) = apply {
            tintColorRed = r
            tintColorGreen = g
            tintColorBlue = b
        }

        internal fun applyTo(c: GlassConfig) {
            cornerRadius?.let { c.cornerRadiusPx = it }
            cornerRadii?.let { c.cornerRadii = it }
            refractionHeight?.let { c.refractionHeight = it }
            refractionOffset?.let { c.refractionOffset = it }
            contrast?.let { c.contrast = it }
            whitePoint?.let { c.whitePoint = it }
            chromaMultiplier?.let { c.chromaMultiplier = it }
            blurRadius?.let { c.blurRadius = it }
            dispersion?.let { c.dispersion = it }
            tintAlpha?.let { c.tintAlpha = it }
            tintColorRed?.let { c.tintColorRed = it }
            tintColorGreen?.let { c.tintColorGreen = it }
            tintColorBlue?.let { c.tintColorBlue = it }
            recordIntervalMs?.let { c.recordIntervalMs = it }
        }
    }
}
