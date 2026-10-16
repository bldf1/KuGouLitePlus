package com.bldf1.kugoulite.plus.glass

import android.view.View
import android.view.ViewGroup

/**
 * 液态玻璃「背景源」定位（Round 34）
 *
 * 从 `FloatingTabBar` 抽出：底栏和设置面板都要找同一棵宿主内容根当玻璃的背景源，
 * 逻辑完全一样，抽出来避免两处各写一份、日后改一处漏一处。
 *
 * 真机层级（5.2.9，`dumpsys activity` 实测）：
 * ```
 * FragmentStackView{ajd 0,0-1220,2712}
 *  ├ FragmentViewHome{f50f595 0,0-1220,2712}        ← 首页 fragment
 *  │  └ MainContainerLayout{92448cb 0,0-1220,2712}  ← ★ 内容根（含双层 pager）
 *  └ FrameLayout{2bc0376 0,0-1220,2712}             ← 底栏挂在**另一支**上
 *     └ fb15e4d{0,2442-1220,2712} → fv2 → fkf → …
 * ```
 * ⚠️ 内容根与底栏是**兄弟**（同在 `FragmentStackView` 下），所以**不能**从玻璃宿主
 * 往上爬去找它 —— 爬不到（Round 28 首次实现就栽在这，日志 `root=-`）。
 * 只能从 decor 往下**带早退的 DFS**：命中即返回，代价约几十个节点。
 *
 * ⚠️ 类名不含混淆（真机 dump 原样显示 `MainContainerLayout`），故按类名后缀匹配。
 */
internal object GlassTarget {

    /** 内容根的类名后缀（混淆无关：真机 dump 就是这个类名） */
    private const val ROOT_CLASS_SUFFIX = "MainContainerLayout"

    /** DFS 限深：decor → … → 内容根实测不到 10 层，24 是宽松上限 */
    private const val MAX_DEPTH = 24

    /**
     * 从 [decor] 往下找内容根。
     *
     * @param mustNotBeAncestorOf 硬门禁：结果**绝不能是它的祖先**。
     *   玻璃靠 `target.draw(rec)` 录背景，若 target 是玻璃自己的祖先，
     *   录制时会把玻璃也画进去 —— 「录屏时又在录屏」的递归，会黑屏/崩。
     *   底栏传 `bar`，设置面板传面板自己。
     */
    fun locate(decor: View, mustNotBeAncestorOf: View? = null): ViewGroup? {
        var found: ViewGroup? = null

        fun walk(v: View, depth: Int) {
            if (found != null || depth > MAX_DEPTH) return
            val vg = v as? ViewGroup ?: return
            for (i in 0 until vg.childCount) {
                val c = vg.getChildAt(i)
                if (c !is ViewGroup) continue
                if (c.javaClass.name.endsWith(ROOT_CLASS_SUFFIX)) {
                    if (mustNotBeAncestorOf == null || !isAncestorOf(c, mustNotBeAncestorOf)) {
                        found = c
                    }
                    return
                }
                walk(c, depth + 1)
                if (found != null) return
            }
        }

        walk(decor, 0)
        return found
    }

    /** [v] 的祖先链里是否有 [anc] */
    fun isAncestorOf(anc: View, v: View): Boolean {
        var p: View? = v.parent as? View
        while (p != null) {
            if (p === anc) return true
            p = p.parent as? View
        }
        return false
    }
}
