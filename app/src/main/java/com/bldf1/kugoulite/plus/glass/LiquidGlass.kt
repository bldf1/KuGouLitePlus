package com.bldf1.kugoulite.plus.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.bldf1.kugoulite.plus.ModuleLog
import java.lang.ref.WeakReference

/**
 * 液态玻璃宿主视图（Round 28）
 *
 * 移植自 **QmDeve/AndroidLiquidGlassView**（MIT，Copyright (c) 2025-2026 Donny Yang）的
 * `com.qmdeve.liquidglass.LiquidGlass`：改为 Kotlin、去 `androidx.annotation`。
 *
 * 它是一个**只画不装**的 FrameLayout：`onDraw` 里把 [LiquidGlassImpl] 录好的
 * RenderNode（= 自己背后那块屏幕内容 + AGSL 折射/色散/模糊）画出来。
 * 形状靠 `clipToOutline` + 圆角 outline 裁成胶囊（着色器在圆角之外也走折射分支，
 * 必须靠这道 clip 收边）。
 *
 * ## 用法（见 `FloatingTabBar` / `NativeSettingsPanel`）
 * 1. `LiquidGlass(ctx, config)` → 2. 挂到宿主某个容器里（MATCH_PARENT）
 * → 3. `init(内容根)` 指定"玻璃背后是哪棵子树"（内容根用 [GlassTarget.locate] 找）。
 *
 * ⚠️ **`target` 绝不能是自己的祖先**：`target.draw(rec)` 会把玻璃自己也画进去，
 * 形成"录屏时又在录屏"的递归（会黑屏/崩）。[init] 内部有一道硬门禁兜底，
 * 但调用方仍应在 `locate` 时就把玻璃自己传进 `mustNotBeAncestorOf`。
 *
 * ⚠️ 形状：默认用 [GlassConfig.cornerRadiusPx] 四角同值；要「只圆上两角」（底部弹层）
 * 就设 [GlassConfig.cornerRadii] = `[r, r, 0, 0]`，此时轮廓改走 `Outline.setPath`。
 */
internal class LiquidGlass(
    context: Context,
    val config: GlassConfig,
) : FrameLayout(context) {

    private var impl: LiquidGlassImpl? = null
    private var target: ViewGroup? = null
    private var listenerAdded = false

    /**
     * 玻璃是否真的在渲染。
     *
     * ⚠️ 调用方（设置面板）必须看这个值决定「底板要不要留实色」：
     * 玻璃初始化失败时 [onDraw] 什么都不画，此时若底板也是透明的，
     * 面板就变成「深色遮罩上写深色字」——彻底不可读。
     */
    val isActive: Boolean get() = impl != null

    private class PreDrawListener(glass: LiquidGlass) : ViewTreeObserver.OnPreDrawListener {
        private val ref = WeakReference(glass)
        override fun onPreDraw(): Boolean {
            ref.get()?.impl?.onPreDraw()
            return true
        }
    }

    private class RoundRectOutlineProvider(private val cornerRadius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
        }
    }

    /**
     * 四角不同半径的轮廓（Round 34：底部弹层只圆上两角）。
     *
     * ⚠️ `Outline.setRoundRect` 只接受**单一**半径，做不到「上圆下直」⇒ 必须走
     * `Outline.setPath`（API 21+）。`addRoundRect` 的 radii 同样是 TL/TR/BR/BL 顺序，
     * 但每个角要 (x, y) 两个值。
     */
    private class PathOutlineProvider(private val radii: FloatArray) : ViewOutlineProvider() {
        private val rect = RectF()
        private val path = Path()

        override fun getOutline(view: View, outline: Outline) {
            rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
            path.rewind()
            path.addRoundRect(
                rect,
                floatArrayOf(
                    radii[0], radii[0], radii[1], radii[1],
                    radii[2], radii[2], radii[3], radii[3],
                ),
                Path.Direction.CW,
            )
            outline.setPath(path)
        }
    }

    private val preDrawListener = PreDrawListener(this)

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        updateOutlineProvider()
    }

    /**
     * 指定玻璃背后的内容根。
     *
     * 幂等：同一个 target 重复调用**不会重建 impl**（重建会重新编译 AGSL，
     * 每次翻页都来一遍会掉帧）。换 target 时才重建。
     */
    fun init(target: ViewGroup) {
        if (this.target === target && impl != null) return
        if (this.target != null) removePreDrawListener()
        this.target = target

        // ⚠️ 最后一道保险：target 是自己的祖先 ⇒ 录制时会把玻璃自己也录进去
        // （「录屏时又在录屏」的递归，会黑屏/崩）。宁可不挂，也不能挂错。
        if (GlassTarget.isAncestorOf(target, this)) {
            ModuleLog.log("液态玻璃: 内容根是自己的祖先，拒绝初始化（防递归录屏）")
            impl = null
            return
        }

        // 液态玻璃依赖 RenderEffect.createRuntimeShaderEffect / RuntimeShader，均 API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            impl = try {
                LiquidGlassImpl(this, target, config)
            } catch (t: Throwable) {
                // AGSL 编译失败 / 设备不支持 RT shader —— 降级为"无填充层"，不影响底栏几何
                ModuleLog.log("液态玻璃初始化失败: " + t.javaClass.simpleName + ": " + t.message)
                null
            }
            addPreDrawListener()
            requestLayout()
            invalidate()
        } else {
            impl = null
            removePreDrawListener()
        }
    }

    override fun onDraw(canvas: Canvas) {
        impl?.draw(canvas)
    }

    /** 参数变更后重新推一次（复用同一实例时用） */
    fun updateParameters() {
        impl?.onPreDraw()
        invalidate()
        updateOutlineProvider()
    }

    private fun updateOutlineProvider() {
        val radii = config.cornerRadii
        if (radii != null && radii.size == 4) {
            // 四角不同（底部弹层 [r,r,0,0]）⇒ 只能走 Path 轮廓
            outlineProvider = PathOutlineProvider(radii)
            clipToOutline = true
            invalidateOutline()
        } else if (config.cornerRadiusPx > 0f) {
            outlineProvider = RoundRectOutlineProvider(config.cornerRadiusPx)
            clipToOutline = true
            invalidateOutline()
        } else {
            outlineProvider = null
            clipToOutline = false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        impl?.onSizeChanged(w, h)
        updateOutlineProvider()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        addPreDrawListener()
    }

    override fun onDetachedFromWindow() {
        removePreDrawListener()
        impl?.dispose()
        super.onDetachedFromWindow()
    }

    private fun addPreDrawListener() {
        val t = target
        if (t != null && !listenerAdded) {
            t.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            listenerAdded = true
        }
    }

    private fun removePreDrawListener() {
        val t = target
        if (t != null && listenerAdded) {
            try {
                t.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            } catch (_: Throwable) {
            }
            listenerAdded = false
        }
    }
}
