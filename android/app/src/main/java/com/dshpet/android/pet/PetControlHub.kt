package com.dshpet.android.pet

import com.dshpet.android.data.PetConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 桌宠可调参数的**功能层**：悬浮菜单、测试页、设置页都调它，不各自去写 DataStore。
 *
 * 设计要点：
 *  - 每个 setter 只负责"把值写进配置"，真正的应用由 [PetOverlayService] 的 watcher 完成
 *    （窗口几何、播放速度、拖动物理、锁定位置都是这样），所以 UI 与运行时彻底解耦，
 *    从哪个入口改，效果都一样。
 *  - 协程跑在**调用方给的作用域**上。悬浮菜单必须传服务级作用域：菜单窗口一关，
 *    ComposeView 被 dispose，`rememberCoroutineScope` 会取消，写配置的协程会被直接掐掉
 *    （这正是"菜单里改了没效果、设置页里改却有用"的原因）。
 */
class PetControlHub(
    private val cfg: PetConfig,
    private val scope: CoroutineScope,
) {
    /** 播放速度（0.5x~2.0x），对随机链和手动播放都生效 */
    fun setPlaybackSpeed(v: Double) = submit { cfg.setPlaybackSpeed(v.coerceIn(0.2, 3.0)) }

    /** 窗口大小档位（0.5/0.72/0.85/1.0，<0 = 满屏） */
    fun setScale(v: Double) = submit { cfg.setScale(v) }

    /** 拖动物理（松手抛出：惯性/重力/反弹） */
    fun setDragPhysics(on: Boolean) = submit { cfg.setDragPhysics(on) }

    /** 锁定位置（固定不动，点击互动仍有效） */
    fun setLockPosition(on: Boolean) = submit { cfg.setLockPosition(on) }

    /** 自动移动开关 */
    fun setNoMove(on: Boolean) = submit { cfg.setNoMove(on) }

    /** 调试：窗口描边 */
    fun setWindowBorder(on: Boolean) = submit { cfg.setWindowBorder(on) }

    private fun submit(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    companion object {
        /**
         * 大小档位的**唯一来源**。
         *
         * 以前悬浮菜单和外貌设置页各写一套（菜单里是 `小/中/大/特大` + 一套 "690dp" 文案，
         * 设置页是另一套 `listOf(0.5 to "小", …)`），于是同一档位两处文案/选中态对不上。
         * 现在两边都遍历这个列表，加档位/改文案只改这里。
         *
         * `value < 0` 表示「满屏」（按全部动画的并集包围盒取临界画幅，见 PetWindowFactory）。
         */
        data class ScalePreset(val value: Double, val label: String)

        val SCALE_PRESETS = listOf(
            ScalePreset(0.5, "小"),
            ScalePreset(0.72, "中"),
            ScalePreset(0.85, "大"),
            ScalePreset(1.0, "特大"),
            ScalePreset(-1.0, "满屏"),
        )

        /** 档位 → 文案；不在预设里（老配置/手改）就显示成 "0.9×" */
        fun labelOf(v: Double): String =
            SCALE_PRESETS.firstOrNull { kotlin.math.abs(it.value - v) < 1e-6 }?.label ?: "${v}×"

        /** 判断两个档位值是否是同一档（浮点比较留 1e-6 容差） */
        fun isSameScale(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 1e-6
    }
}
