package com.dshpet.android.compat

/**
 * 机型适配的**唯一入口**（处理链）。
 *
 * 设计目标（用户要求）：适配和业务解耦，"像日志一样"可观测。
 *
 *  - 业务代码（[com.dshpet.android.pet.PetOverlayService]）只消费 [WindowPlan]，
 *    **不许**再出现 `Build.MANUFACTURER == "Xiaomi"` / `if (sdk >= 30)` 这类判断；
 *  - 所有平台差异收敛成 [PlatformFacts]（启动时探测一次）；
 *  - 决策由 [WindowPolicy.rules] 这条**处理链**逐条改写，顺序即优先级；
 *  - 每次决策落一行固定格式日志 `Compat/1 ...`，出问题 grep 这一行就知道走了哪条规则。
 *
 * 新增机型/新系统行为时的正确做法：**加一条 [PlanRule]**，不要改业务代码。
 */
data class PlatformFacts(
    /** Build.VERSION.SDK_INT */
    val sdk: Int,
    /**
     * 能否设置窗口的"可触摸区域"（`ViewTreeObserver$InternalInsetsInfo.touchableRegion`）。
     * Android 12+ 起该字段在 hidden API 名单里，反射会被 deny → false。
     */
    val touchRegionReflectable: Boolean,
    /** 是否支持 `fitInsetsTypes = 0`（Android 11+；用于让窗口坐标=整屏坐标） */
    val fitInsetsSupported: Boolean,
    /**
     * 是否支持真毛玻璃（`Modifier.blur` / `RenderEffect`，Android 12+）。
     * 业务代码只判断这个能力，不要直接写 `SDK_INT >= 31`。
     */
    val blurSupported: Boolean = sdk >= 31,
    /** GPU 渲染器名（诊断用，不参与决策） */
    val gpu: String = "unknown",
) {
    /** 固定格式，便于 grep：`Compat/1 sdk=36 touchRegion=false splitTouch=true ...` */
    fun logLine(plan: WindowPlan): String =
        "Compat/1 sdk=$sdk touchRegion=$touchRegionReflectable fitInsets=$fitInsetsSupported " +
            "blur=$blurSupported union=${plan.unionWindow} fixed=${plan.fixedSize} " +
            "splitTouch=${plan.splitTouchWindow} inflate=${plan.inflatePx} rule=${plan.rule} gpu=$gpu"
}

/**
 * 窗口方案：适配层算出来的**结论**。业务代码只读它，不关心为什么。
 */
data class WindowPlan(
    /** true = 所有动画共用一个"并集"窗口（尺寸恒定，不 resize） */
    val unionWindow: Boolean = true,
    /** 四边额外撑开多少像素（原生 touchableRegion 用不到时留的余量） */
    val inflatePx: Int = 0,
    /** true = 按动画包围盒定尺寸（会 resize，仅在没有更好的办法时用） */
    val fixedSize: Boolean = false,
    /** true = 拆成"视觉窗口(NOT_TOUCHABLE) + 触摸窗口"，等效 touchableRegion */
    val splitTouchWindow: Boolean = false,
    /** 命中哪条规则（写进日志，排障用） */
    val rule: String = "default",
)

/** 一条适配规则：只改自己关心的字段，返回新的方案 */
fun interface PlanRule {
    fun apply(plan: WindowPlan, facts: PlatformFacts): WindowPlan
}

object WindowPolicy {

    /**
     * 处理链：**从通用到特殊**，后面的规则可以覆盖前面的。
     *
     * 目前两条就够：
     *  1. 能设 touchableRegion（一般 ≤ Android 11）→ 并集窗口 + 系统级窄触摸区，最省事；
     *  2. 设不了（Android 12+）→ 并集窗口 + 双窗口拆分，行为与 1 等价。
     *
     * 注意：两条都选"并集窗口"，因为**尺寸恒定 = 不 resize** 是"撞边闪现"的根治条件。
     */
    private val rules: List<PlanRule> = listOf(
        PlanRule { plan, facts ->
            if (facts.touchRegionReflectable) {
                plan.copy(unionWindow = true, fixedSize = false, splitTouchWindow = false, inflatePx = 0, rule = "native-touch-region")
            } else plan
        },
        PlanRule { plan, facts ->
            if (!facts.touchRegionReflectable) {
                // 并集窗口 + 拆触摸窗口（用户认可的方案；单窗口方案已回退，见 HANDOFF ㊾）
                plan.copy(unionWindow = true, fixedSize = false, splitTouchWindow = true, inflatePx = 0, rule = "split-touch-window")
            } else plan
        },
    )

    /** 跑一遍处理链，得到最终方案 */
    fun plan(facts: PlatformFacts): WindowPlan =
        rules.fold(WindowPlan()) { acc, rule -> rule.apply(acc, facts) }
}
