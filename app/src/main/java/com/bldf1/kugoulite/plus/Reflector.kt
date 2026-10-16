package com.bldf1.kugoulite.plus

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 公共反射工具：统一 Method 查找与缓存。
 *
 * 原本 PopupBlocker/NetCapture/UiCleaner/AutoClicker/IntegrityBypass/
 * SettingsEntry/ChannelGestureBlocker 各自复制一份 findMethod；
 * 查找结果按「类#方法#参数签名」进程级缓存，热路径不再重复遍历方法表。
 */
object Reflector {

    private val cache = ConcurrentHashMap<String, Method?>()

    /**
     * 查找方法（先声明方法后公共方法），找不到返回 null（结果缓存）
     */
    fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>?): Method? {
        val key = clazz.name + "#" + name + "#" + paramTypes.joinToString(",") { it?.name ?: "null" }
        return cache.getOrPut(key) {
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
    }
}
