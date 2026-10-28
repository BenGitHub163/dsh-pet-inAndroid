package com.dshpet.android.pet

/**
 * 桌宠窗口几何工厂。
 *
 * 输入：设备的屏幕参数（宽/高/导航条高度）+ 动画目录里的**每段动画非透明像素包围盒**
 * 输出：某段动画、某个缩放档位下，悬浮窗应该多大、视频层应该怎么摆。
 *
 * 为什么需要它：
 * 素材是 16:9 画布，人物只占其中一块，而且**每段动画都不一样**——
 * 实测（`assets/pet/frames.json`）宽度 35%~98%、高度 76%~100%。
 * 旧实现是"固定裁中间 42%"，于是：
 *  - 窄动画（如写代码 35%）四周全是透明死区，悬浮窗白白吃触摸；
 *  - 宽动画（如放烟花 93%、孔雀开屏 98%）会被裁掉一截。
 * 现在窗口 = 该动画自己的包围盒，画面边缘不会再被切断，也不再有大片死区。
 *
 * 约定：窗口矩形**就是**人物的包围盒（加几像素 padding），
 * 因此人物质心/脚底的屏幕坐标 = 窗口的 (中心 x, 底边 y)，改窗口大小时按这个锚点换算即可。
 */
class PetWindowFactory(
    private val screenW: Int,
    private val screenH: Int,
    /** 导航条（手势条）高度，满屏档位要扣掉 */
    private val bottomInset: Int,
    /** 动画名 → [l, t, r, b]（0..1） */
    private val frames: Map<String, FloatArray>,
    /** 所有动画的并集包围盒，用于「满屏」档位 */
    private val globalBox: FloatArray,
) {

    /** 一段动画在当前档位下的窗口与图层布局 */
    data class Geometry(
        /** 视频层（16:9 画布）像素宽高 */
        val canvasW: Int,
        val canvasH: Int,
        /** 悬浮窗像素宽高（= 包围盒 + padding + 头顶预留） */
        val winW: Int,
        val winH: Int,
        /** 视频层相对窗口左上角的平移（负值 = 把画布往左上推，让包围盒左上角对齐窗口） */
        val offsetX: Int,
        val offsetY: Int,
        /**
         * 人物包围盒**顶边**在窗口坐标系里的 y（像素）＝ 光环圆心该放的位置。
         * 语音运行时才有头顶预留（[haloPx]），所以这个值会随 [haloPx] 下移。
         */
        val headTopPx: Int = 0,
        /** 头顶给光环预留的高度（0 = 没开语音，窗口就是纯包围盒） */
        val haloPx: Int = 0,
        /**
         * **人物实际像素**（该动画的非透明包围盒）相对窗口四边的留白，像素。
         *
         * 为什么要它：窗口比"看得见的鱼身"大一圈——`padPx`（防采样裁边）+ [MARGIN]（发光/羽化余量，
         * 按画幅 3% 算，大档位下每边就有十几到几十像素）。拖动/抛掷如果按**窗口**和屏幕边框碰撞，
         * 人就会在离边框还有一圈透明留白的地方弹回来。用这四个值把窗口换算成"鱼身矩形"，
         * 碰撞就是"像素贴边"了。
         */
        val insetLeft: Int = 0,
        val insetTop: Int = 0,
        val insetRight: Int = 0,
        val insetBottom: Int = 0,
        /** 实际采用包围盒的动画名（未知动画时为 null） */
        val anim: String?,
    )

    /** 缩放档位 → 画布宽度。`scale < 0` 表示「满屏」档 */
    fun canvasWidth(scale: Double): Int {
        if (scale < 0) return fitCanvasWidth()
        val frac = 0.42 * scale / 0.72
        return (screenW * frac).toInt().coerceIn(MIN_CANVAS_W, screenW * 3)
    }

    /**
     * 「满屏」档位的画布宽度：让**所有动画的并集**都塞得进屏幕（宽、高都不超）。
     * 也就是"再大就一定会有动画被切断"的那个临界值。
     */
    fun fitCanvasWidth(): Int {
        val gw = (globalBox[2] - globalBox[0]).coerceAtLeast(0.05f)
        val gh = (globalBox[3] - globalBox[1]).coerceAtLeast(0.05f)
        val byWidth = screenW / gw
        val usableH = (screenH - bottomInset).toFloat()
        val byHeight = usableH / (gh * 9f / 16f)      // canvasH = canvasW * 9/16
        return minOf(byWidth, byHeight).toInt().coerceIn(MIN_CANVAS_W, screenW * 3)
    }

    /**
     * 取某段动画的几何。
     *
     * **窗口尺寸固定**（= 所有动画的并集包围盒），换动画时只改视频层的平移和四边留白。
     * 这是 2026-10-26 的关键改动：以前窗口 = 当前动画的包围盒，于是**每次换动画都要
     * `wm.updateViewLayout` 改窗口宽高** → SurfaceView 缓冲重分配 → 掉一帧（用户看到的就是
     * "动画播完/点一下桌宠就卡一下"）。现在窗口一次定死，换动画只是 `translationX/Y` 变一下。
     * 触摸区域由服务侧用 `OnComputeInternalInsetsListener` 收窄到人物像素（否则窗口变大后
     * 透明区域也会吃掉点击）。
     *
     * @param anim  动画名；null/未知 → 用待机动画的包围盒兜底
     * @param scale 缩放档位（<0 = 满屏）
     * @param padPx 包围盒四周留的像素，防止 shader 采样在边缘被裁
     * @param haloPx 头顶额外留的高度（语音运行时传光环直径）：窗口整体向上长，人物位置不变。
     */
    fun geometryFor(
        anim: String?,
        scale: Double,
        padPx: Int,
        haloPx: Int = 0,
        union: Boolean = true,
        inflate: Int = 0,
        fixedSize: Boolean = false,
        /** fixedSize 模式下来定窗口尺寸的"常用动画"名字；为空则用全动画最大值 */
        coreAnims: List<String>? = null,
    ): Geometry {
        val cw = canvasWidth(scale)
        val ch = (cw * 9f / 16f)
        val box = resolveBox(anim)

        // ---- 窗口尺寸（三种策略，按能力选择）----
        // union = true  ：全局并集（含 MARGIN），换动画**绝不** resize；靠 touchableRegion 收窄触摸。
        //                 只在能收窄触摸的系统上用（hidden API 可用）。
        // fixedSize     ：按"所有动画里**最大的单个包围盒**"定死尺寸，每个动画在自己的窗口里居中/贴底。
        //                 用于 **touchableRegion 不可用** 的系统（Android 12+ deny 反射）：
        //                 既不像 per-animation 那样每次换动画都 resize（换动画卡顿/瞬移的根因），
        //                 也不像并集那样撑到接近整屏（透明区吃点击）。
        // 都关          ：按当前动画自己的包围盒（最小透明区，但每次换动画会 resize）。
        // inflate       ：四边各再撑开一点，让双指弹弓的第二根手指能落进窗口。
        val base = if (union) globalBox else box
        val ul = (base[0] - MARGIN).coerceIn(0f, 1f)
        val ut = (base[1] - MARGIN).coerceIn(0f, 1f)
        val ur = (base[2] + MARGIN).coerceIn(0f, 1f)
        val ub = (base[3] + MARGIN).coerceIn(0f, 1f)
        val pad = inflate.coerceAtLeast(0)
        val spanW: Int
        val spanH: Int
        if (fixedSize && !union) {
            // 尺寸 = 「**常用动画**里最大的单个包围盒」（idle/turn/move/click/drag）：
            //  · 这些动画之间切换（包括"拿起来"那一下）窗口尺寸**完全不变** → 不 resize、不卡顿；
            //  · 随机大动作（环绕展示之类）偶尔 resize，但那会儿用户在干别的事，可以接受；
            //  · 再叠一个画幅上限，防止某一类动画本身就把窗口撑到接近整屏。
            // 为什么不用"全动画最大值"：会被个别宽动画拖到几乎满屏，透明区把点击全吃掉。
            val capW = (cw * FIXED_CAP_FRAC).toInt()
            val capH = (ch * FIXED_CAP_FRAC).toInt()
            val coreList = coreAnims?.takeIf { it.isNotEmpty() }
            val refW = if (coreList != null) boxMaxWidth(coreList) else maxBoxWidth()
            val refH = if (coreList != null) boxMaxHeight(coreList) else maxBoxHeight()
            val maxW = ((refW + MARGIN * 2) * cw).toInt()
            val maxH = ((refH + MARGIN * 2) * ch).toInt()
            val curW = ((box[2] - box[0] + MARGIN * 2) * cw).toInt()
            val curH = ((box[3] - box[1] + MARGIN * 2) * ch).toInt()
            spanW = maxOf(minOf(maxW, capW), curW).coerceAtLeast(1)
            spanH = maxOf(minOf(maxH, capH), curH).coerceAtLeast(1)
        } else {
            spanW = ((ur - ul) * cw).toInt()
            spanH = ((ub - ut) * ch).toInt()
        }
        val rawW = spanW + padPx * 2 + pad * 2
        val rawH = spanH + padPx * 2 + pad * 2
        // 头顶预留不能把窗口顶出屏幕
        val halo = haloPx.coerceAtLeast(0).coerceAtMost((screenH - rawH).coerceAtLeast(0))
        val winW0 = rawW.coerceIn(MIN_WIN, screenW)
        val winH0 = (rawH + halo).coerceIn(MIN_WIN, screenH)
        // 兜底：既不是并集、也没开 fixedSize 时才需要"取整到 STEP"，把尺寸抖动压成少数几档
        val needQuantize = !union && !fixedSize
        val winW = if (needQuantize) quantize(winW0, screenW) else winW0
        val winH = if (needQuantize) quantize(winH0, screenH) else winH0

        // ---- 该动画的包围盒在窗口里：水平居中、底部对齐（脚底锚点 = 窗口底边）----
        val contentW = (winW - padPx * 2).toFloat()
        val contentH = (winH - padPx * 2 - halo).toFloat()
        val boxW = (box[2] - box[0]) * cw
        val boxH = (box[3] - box[1]) * ch
        val x0 = padPx + (contentW - boxW) / 2f          // 人物左边缘在窗口坐标系里的 x
        val y0 = padPx + halo + (contentH - boxH)        // 人物上边缘在窗口坐标系里的 y

        val insetL = x0.toInt()
        val insetT = y0.toInt()
        val insetR = (winW - x0 - boxW).toInt()
        val insetB = (winH - y0 - boxH).toInt()

        return Geometry(
            canvasW = cw,
            canvasH = ch.toInt(),
            winW = winW,
            winH = winH,
            // 视频层相对窗口左上角的平移：让画布里的包围盒落到 (x0, y0)
            offsetX = (x0 - box[0] * cw).toInt(),
            offsetY = (y0 - box[1] * ch).toInt(),
            headTopPx = y0.toInt(),
            haloPx = halo,
            insetLeft = insetL.coerceIn(0, winW / 2),
            insetTop = insetT.coerceIn(0, winH / 2),
            insetRight = insetR.coerceIn(0, winW / 2),
            insetBottom = insetB.coerceIn(0, winH / 2),
            anim = if (frames.containsKey(anim)) anim else null,
        )
    }

    /** 把窗口尺寸向上取整到 [QUANTIZE_STEP] 的倍数（仅在"按动画包围盒"模式下兜底用） */
    private fun quantize(v: Int, max: Int): Int {
        val q = ((v + QUANTIZE_STEP - 1) / QUANTIZE_STEP) * QUANTIZE_STEP
        return q.coerceIn(MIN_WIN, max)
    }

    /** 所有动画里**最宽的单个包围盒**（归一化宽度，0..1）——fixedSize 模式用它定窗口宽度 */
    private fun maxBoxWidth(): Float =
        if (frames.isEmpty()) 1f else frames.values.maxOf { (it[2] - it[0]).coerceAtLeast(0f) }

    /** 指定动画集合里最宽的包围盒（归一化） */
    private fun boxMaxWidth(names: List<String>): Float {
        val list = names.mapNotNull { frames[it] }
        return if (list.isEmpty()) maxBoxWidth() else list.maxOf { (it[2] - it[0]).coerceAtLeast(0f) }
    }

    /** 指定动画集合里最高的包围盒（归一化） */
    private fun boxMaxHeight(names: List<String>): Float {
        val list = names.mapNotNull { frames[it] }
        return if (list.isEmpty()) maxBoxHeight() else list.maxOf { (it[3] - it[1]).coerceAtLeast(0f) }
    }

    /** 所有动画里**最高的单个包围盒**（归一化高度，0..1） */
    private fun maxBoxHeight(): Float =
        if (frames.isEmpty()) 1f else frames.values.maxOf { (it[3] - it[1]).coerceAtLeast(0f) }

    /** 该动画在画布里的包围盒；没有数据时退回全局并集 */
    private fun resolveBox(anim: String?): FloatArray {
        if (anim != null) frames[anim]?.let { return it }
        frames[IDLE_ANIM]?.let { return it }
        return globalBox
    }

    companion object {
        /** 待机动画名（兜底用；和 manifest.json 里的一致） */
        const val IDLE_ANIM = "待机呼吸休闲"

        /** 包围盒四边各放宽多少（相对画幅），给半透明羽化/发光留余量 */
        private const val MARGIN = 0.03f

        /** "按动画包围盒"模式下窗口尺寸的取整粒度（px）：越大越省 resize，透明边距也越大 */
        private const val QUANTIZE_STEP = 64

        /** fixedSize 模式下窗口上限 = 画幅的这个比例（超出的个别动画才允许 resize） */
        private const val FIXED_CAP_FRAC = 0.88f
        private const val MIN_CANVAS_W = 120
        private const val MIN_WIN = 60
    }
}
