package com.dshpet.android.pet

/**
 * 桌宠主服务（前台 Service）——**整个 App 的运行时中枢**。
 *
 * 它一个人干这些活：
 *  1. **悬浮窗**：创建 `TYPE_APPLICATION_OVERLAY` 窗口，里面依次叠 `PetVideoView`（视频）
 *     和 `PetVoiceWaveView`（语音波纹）；窗口尺寸按 `scale` 计算，可从屏幕边缘溢出。
 *  2. **动画链**：把"动画播完"回调喂给 [PetEngine]，由引擎决定下一段播什么；移动时由
 *     [PetEngine] 的 MovePlan 驱动窗口位置。
 *  3. **手势**：按下/拖动/抬起 → 拖拽、抛掷物理（[CollisionHub] 处理多只互撞）、
 *     点击计数（[TapSequencer]）→ 单击停语音 / 双击 QuickChat / 三击起语音 / 长按开菜单。
 *  4. **配置响应**：`observeSettings()` 把 DataStore 的每个 key 映射成一个 watcher，
 *     改设置即时生效（尺寸、透明度、速度、物理、气泡、灵动岛、语音…）。
 *  5. **语音宿主**：持有 [com.dshpet.android.voice.core.VoiceLoop]，并把波纹状态推给 View。
 *  6. **多开**：`PetOverlayService1..9` 是只覆盖 `defaultInstanceId` 的空壳子类，
 *     靠"一个 Service 类只能有一个实例"这一限制来伪造多只桌宠。
 *
 * 生命周期要点：`onStartCommand` 每次都会重新校验悬浮窗权限；`START_STICKY` 让系统杀掉后
 * 能自动重建；`initFailed` + `consecutiveErrors` 两道熔断防止崩溃/重启死循环。
 *
 * 线程模型：窗口/动画/手势全在**主线程**；网络（TTS/LLM）在 [com.dshpet.android.voice.core.VoiceLoop]
 * 的协程里；配置监听走 `scope`（主线程调度器 + DataStore 自身 IO）。
 */

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.media.SoundPool
import androidx.core.app.NotificationCompat
import com.dshpet.android.MainActivity
import com.dshpet.android.R
import com.dshpet.android.chat.ChatActivity
import com.dshpet.android.data.PetConfig
import com.dshpet.android.data.applyHideRecentsPolicy
import com.dshpet.android.data.PetState
import com.dshpet.android.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 桌宠悬浮窗前台服务（每只桌宠一个实例，多开互不干扰）。
 *
 * - TYPE_APPLICATION_OVERLAY 透明窗口，显示在任意应用之上；
 * - 手势：点击 = 点击回应动画 + Q 弹；拖动 = 跟手移动（可物理抛掷）；
 *   长按 = MD3 菜单；"仅长按可拖动"模式下长按后拖动才生效；
 * - 动画链、位置持久化（按屏幕比例换算 dp）、多实例隔离；
 * - 前台通知提供：显示/隐藏、AI 对话、设置、退出。
 */
open class PetOverlayService : Service() {

    /** 本服务类负责的实例号（系统重启 null intent 时的默认值） */
    protected open val defaultInstanceId: Int get() = 0

    companion object {
        const val CHANNEL_ID = "pet"

        /**
         * 设置页是否在前台（同进程直接置位，不发 Intent —— 发 Intent 会把没在跑的桌宠服务拉起来）。
         *
         * 用途：设置页在前台时**不新开散步**。Compose 切页/滚动会把主线程占满，而散步是逐帧改
         * 窗口位置、跑在同一个主线程的 Choreographer 上 → 窗口移动一顿一顿，看起来就是
         * "切页越快桌宠越卡"（动画本身照常播：实测 GL 仍有 47~48fps）。
         */
        @Volatile
        var uiForeground = false
        const val EXTRA_INSTANCE = "instance_id"

        /** 让运行中的桌宠播指定动画（「测试 → 桌宠动画测试」用） */
        const val ACTION_PLAY = "play_anim"
        const val EXTRA_ANIM = "anim_name"

        /** 只改播放速度，不重启当前动画（动画测试页的滑杆） */
        const val ACTION_PLAY_SPEED = "play_speed"
        const val EXTRA_SPEED = "play_speed_value"
        private const val TAG = "PetOverlay"

        /** 服务子类硬上限（0..9 共 10 个实例槽位；设置里可配 0=不限） */
        const val SLOT_COUNT = 10

        /**
         * 语音光环的头顶预留高度 = 画布宽度 × 这个比例。
         * 越大的档位光环越大；底部夹到 24~220px（见 [geometryFor]）。
         */
        private const val HALO_CANVAS_FRAC = 0.11f

        /** 首次"自说自话"的随机区间（秒）：开开关后能立刻看到效果 */
        private const val FIRST_TALK_MIN_SEC = 8.0
        private const val FIRST_TALK_MAX_SEC = 15.0

        /** 拖拽轨迹最多留几个采样点（120Hz ≈ 270ms，够估松手初速；再多只是浪费） */
        private const val TRAIL_MAX = 32

        // ---- 弹弓（拖拽中按住第二根手指蓄力，松手发射）----
        /** 拉距 → 初速的换算：22 倍（100px 拉距 ≈ 2200px/s） */
        private const val SLINGSHOT_PX_TO_SPEED = 22.0
        /** 拉距低于这个值不算蓄力（px） */
        private const val SLINGSHOT_MIN_PULL = 40.0
        /** 低于这个初速不发射（px/s） */
        private const val SLINGSHOT_MIN_SPEED = 700.0
        /** 蓄力时桌宠跟着手指走的比例（0.35 = 只跟一点点，像橡皮筋被扯开） */
        private const val SLINGSHOT_FOLLOW = 0.35f

        /** 物理子步长上限（秒）：帧被拖慢时拆成小步，避免桌宠"瞬移" */
        private const val MAX_PHYS_SUBSTEP = 0.008

        /** 触摸区域最小宽高（px）：小于它就认为算错了，退回整窗可触摸（否则会出现"完全点不到"） */
        private const val MIN_TOUCH_REGION_PX = 24

        /** 平面重力小于这个比例（相对引擎 GRAVITY）就算"手机平放"：此时没有方向，桌宠飘着并慢慢减速 */
        private const val FLAT_GRAVITY_FRAC = 0.15

        /**
         * 不能用 touchableRegion 时，窗口四边各多撑开多少像素。
         *
         * 作用有两个：① 双指弹弓时第二根手指能落进窗口（小尺寸下窗口只有一两百像素，根本放不下两根手指）；
         * ② 点按判定容错。代价是"透明区吃掉点击"的范围变大 —— 所以只在没有 touchableRegion 的系统上用。
         */
        private const val TOUCH_INFLATE_PX = 0

        /** 重力唤醒：连续这么多次发现"没贴住重力那一面"才真的起抛（33ms 一次 → 2 次≈66ms） */
        private const val GRAVITY_WAKE_HITS = 2
        /** 重力唤醒冷却：每次落停后这么久内不再唤醒（太小会抖，太大会显得"迟钝"） */
        private const val GRAVITY_WAKE_COOLDOWN_MS = 200L

        // ---- 甩手机（重力感应开着时）----
        /** 合加速度相对 1g 的偏差超过这么多（m/s²）就算"甩了一下"：6 ≈ 0.6g（太小会被走路/放桌上误触发） */
        private const val SHAKE_MIN_IMPACT = 4.5
        /** 两次甩动之间至少隔这么久（毫秒） */
        private const val SHAKE_COOLDOWN_MS = 350L
        /** 冲击强度 → 初速的换算（px/s 每 m/s²）：6 → 3120，10 → 5200（再受「甩出力度」档位上限约束） */
        private const val SHAKE_SPEED_PER_G = 760.0
        /** 甩动至少给这么多初速，保证"看得到"（轻甩也能把桌宠弹起来） */
        private const val SHAKE_MIN_SPEED = 2200.0

        /**
         * 右下角允许的出屏比例（只作用于右、下两边）。
         * 0.10 = 最多把窗口的 10% 放到屏幕外：大档位时人贴着角"蹲着"，而不是离角老远。
         * 左、上仍然是 0（出屏会把人切掉一半）。
         */
        private const val CORNER_OVERFLOW_FRAC = 0.10f

        /** 进程级多开上限缓存（设置可调；0 = 无限制；PetApp 启动时同步） */
        @Volatile
        var maxInstances: Int = 4

        /** 桌宠可见性（灵动岛单击切换） */
        @Volatile
        var petHidden = false

        /** 实例号 → 服务类（Android 同一 Service 类只有一个对象，
         *  多开必须用不同服务类隔离窗口/引擎状态） */
        fun serviceClassFor(id: Int): Class<*> = when (id) {
            1 -> PetOverlayService1::class.java
            2 -> PetOverlayService2::class.java
            3 -> PetOverlayService3::class.java
            4 -> PetOverlayService4::class.java
            5 -> PetOverlayService5::class.java
            6 -> PetOverlayService6::class.java
            7 -> PetOverlayService7::class.java
            8 -> PetOverlayService8::class.java
            9 -> PetOverlayService9::class.java
            else -> PetOverlayService::class.java
        }

        fun intent(ctx: Context, instanceId: Int = 0): Intent =
            Intent(ctx, serviceClassFor(instanceId)).putExtra(EXTRA_INSTANCE, instanceId)

        /** 当前活跃实例号（含 0） */
        fun activeInstanceIds(): List<Int> = synchronized(activeInstances) { activeInstances.toList() }

        /**
         * 确保桌宠服务在运行。
         * @return false = 系统拒绝了这次启动（后台限制 / 没有悬浮窗权限等），调用方可以据此提示用户
         */
        fun ensureRunning(ctx: Context, instanceId: Int = 0, persist: Boolean = true): Boolean {
            return try {
                if (persist) ctx.startForegroundService(intent(ctx, instanceId))
                else ctx.startService(intent(ctx, instanceId))
                true
            } catch (e: Exception) {
                android.util.Log.e("DshSvc", "ensureRunning FAILED persist=$persist instance=$instanceId", e)
                AppLog.u("桌宠", "启动失败：" + (e.message ?: e.javaClass.simpleName))
                AppLog.log("SVC", "ensureRunning 失败(${if (persist) "前台" else "后台"}): ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }

        fun stopAll(ctx: Context) {
            synchronized(activeInstances) {
                val ids = activeInstances.toList()
                ids.forEach { ctx.stopService(intent(ctx, it)) }
            }
            // 停掉所有服务类（含未在 activeInstances 中的）
            for (id in 0 until SLOT_COUNT) {
                runCatching { ctx.stopService(intent(ctx, id)) }
            }
        }

        private val activeInstances = mutableSetOf<Int>()

        /** 当前活着的实例 id（看门狗用来判断"桌宠还在不在"） */
        fun runningInstances(): Set<Int> = synchronized(activeInstances) { activeInstances.toSet() }

        /**
         * 进程内"用户想要桌宠"的标记。
         *
         * 不能只依赖 DataStore 的 `pet_running`：`onTaskRemoved` 是同步回调，
         * 读不到协程里的值；而且服务被系统杀掉后进程可能已经没了。
         */
        @Volatile
        var wantRunning = false

        // ---- 手势计数改为进程级共享：多实例时只有一个"真正的手势"被结算 ----
        private var gTapCount = 0
        private var gFirstTapAt = 0L
        private var gLastActionAt = 0L
        private var gTapPending: Runnable? = null
        private val gMain = android.os.Handler(android.os.Looper.getMainLooper())

        /** 本进程内某实例初始化失败：避免 START_STICKY 无限重启循环 */
        private val initFailed = mutableSetOf<Int>()
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
                kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                    // 协程内异常不得让应用崩溃，写入内置日志
                    AppLog.log("SCOPE", android.util.Log.getStackTraceString(e))
                }
    )
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var config: PetConfig
    private var instanceId = 0
    private var stateStore: PetState? = null

    private lateinit var wm: WindowManager
    private var container: View? = null
    private lateinit var videoView: PetVideoView
    private var params: WindowManager.LayoutParams? = null

    // ---- 双窗口：视觉窗口（画面）+ 触摸窗口（收手势）----
    // 背景：Android 12+ 设不了 `touchableRegion`（`ViewTreeObserver$InternalInsetsInfo` /
    // `WindowManager$LayoutParams` 里的字段都在 hidden API 名单里，反射被 deny），
    // 于是大窗口的透明区会把底下应用的点击全吃掉（Android 11 可以用 touchableRegion 收窄）。
    // 公开 API 里唯一可行的等效做法：**视觉窗口 FLAG_NOT_TOUCHABLE + 一个只有人物大小的小窗口收手势**。
    // 效果与一加的 touchableRegion 一致：只有人物那一块能点，其它位置穿透到底下的应用。
    private var touchView: android.view.View? = null

    /** 平台事实（探测一次；业务代码不直接判 Build.*） */
    private val platformFacts: com.dshpet.android.compat.PlatformFacts by lazy {
        com.dshpet.android.compat.PlatformFacts(
            sdk = android.os.Build.VERSION.SDK_INT,
            touchRegionReflectable = touchRegionSupported,
            fitInsetsSupported = android.os.Build.VERSION.SDK_INT >= 30,
        )
    }

    /** 适配处理链算出的窗口方案（业务代码只消费它） */
    private val windowPlan: com.dshpet.android.compat.WindowPlan by lazy {
        com.dshpet.android.compat.WindowPolicy.plan(platformFacts)
    }
    private var touchParams: WindowManager.LayoutParams? = null
    private var bubble: SpeechBubble? = null
    private var menuWindow: PetMenu? = null
    private var easterEggs = mutableListOf<EasterEggPopup>()

    private lateinit var engine: PetEngine
    private lateinit var catalog: PetCatalog
    private var soundPool: SoundPool? = null
    private var clickSoundId = 0

    // ---- 运行时设置快照（协程监听 DataStore 更新）----
    private var density = 1f
    private var curScale = 0.72
    private var curOpacity = 100
    internal var curSpeed = PetConfig.DEFAULT_PLAYBACK_SPEED
    private var curNoMove = false
    internal var curLock = false
    private var curShiftDrag = false
    internal var curPhysics = false
    internal var curCollision = true
    private var curGap = 0.0

    /** 调试：是否画出悬浮窗矩形 */
    private var curBorder = false

    /**
     * 语音光环是否占着"头顶预留区"。
     * 语音 running 时窗口要向上多留一段（见 [PetWindowFactory.geometryFor] 的 haloPx），
     * 由 `waveTick` 在状态翻转时触发一次几何重算。
     */
    private var curHaloActive = false

    /** initPet 是否已经开始（onStartCommand 只跑一次初始化，见那里的防重入说明） */
    private var initStarted = false

    /**
     * 人物**实际像素**相对窗口四边的留白（见 [PetWindowFactory.Geometry.insetLeft]）。
     *
     * 窗口 = 包围盒 + pad + 3% 发光余量，所以鱼身比窗口小一圈。拖动/抛掷的边界一律按
     * `窗口 + 这四个留白` 换算成"鱼身矩形"再和屏幕边框碰，人才能真的贴到边上，
     * 而不是隔着一圈透明留白就弹回来。
     */
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0
    private var insetB = 0

    /** 长按判定：普通模式（弹菜单/拖动）与"仅长按可拖动"模式，比系统 500ms 快 20% */
    private val LONG_PRESS_MS = 400L
    private val LONG_PRESS_SHIFT_MS = 240L

    /** 窗口还没建好时收到的「播放指定动画」请求，initPet 末尾补播 */
    private var pendingPlayAnim: String? = null

    /** 动画测试台指定的播放速度（null = 用设置里的速度）；循环播放时同样生效 */
    private var playSpeedOverride: Float? = null
    private var curSelfTalk = false
    private var curSelfTalkTexts: List<String> = emptyList()
    private var curSelfTalkDurationMs = 3200L
    private var curSelfTalkMinSec = 20
    private var curSelfTalkMaxSec = 60
    /** 台词交给 DeepSeek 现场生成 / 生成后顺便念出来（设置里可开，见「外观 → 自言自语气泡」） */
    private var curSelfTalkAi = false
    private var curSelfTalkAiVoice = false
    /** 自定义气泡底色（"#AARRGGBB"，空 = 跟随样式） */
    private var curBubbleColor = ""
    /** AI 台词请求在途（防连点刷请求） */
    private var selfTalkAiBusy = false
    private var selfTalkRunnable: Runnable? = null
    private var curClickSound = true
    private var curClickBalance = false
    private var curClickSelfTalk = false
    private var visible = true
    private var consecutiveErrors = 0
    private var settingsJobs = mutableListOf<Job>()

    

    // ---- 手势状态 ----
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var grabOffsetX = 0f  // 按下时固定：手指 - 窗口左上角（px）
    private var grabOffsetY = 0f
    private var dragging = false
    /**
     * 拖拽期间换了动画但**故意不动窗口几何**（见 [playAnimation]），松手后补一次。
     * 不补的话窗口会一直停在上一段动画的尺寸上（脚底/裁剪都会偏）。
     */
    private var pendingGeometryAfterDrag = false
    /**
     * 抛掷期间**先不切回待机动画**（见 ACTION_UP 的注释）：等落停后再 `engine.onDragEnd()`。
     * 立即切的话要换解码器 + 改窗口尺寸，正好卡在发射那一瞬间。
     */
    private var deferDragEnd = false
    private var longPressFired = false
    private var justDragged = false
    private var pressActive = false
    private val longPressRunnable = Runnable { onLongPress() }
    private var trail = mutableListOf<Triple<Long, Float, Float>>() // (uptimeMs, rawX, rawY)
    private var physPos = doubleArrayOf(0.0, 0.0)
    private var physVel = doubleArrayOf(0.0, 0.0)
    private var physMode: String? = null // null / throw（拖拽期间不再有 "drag" 弹簧态，见 ACTION_MOVE）
    /** 上一次推给碰撞中枢的速度：用来区分"中枢改过"和"就是我上次推的值" */
    private var pushedVx = 0.0
    private var pushedVy = 0.0

    // ---- 重力感应（加速度计）----
    private var curGravitySensor = false
    private var sensorManager: android.hardware.SensorManager? = null
    private var accelListener: android.hardware.SensorEventListener? = null

    /** 加速度计回调专用线程（避免 50Hz 回调挤占主线程，见 setGravitySensor 里的注释） */
    private var sensorThread: android.os.HandlerThread? = null
    /** 已换算到屏幕坐标系的加速度（m/s²，x 右为正、y 下为正），低通滤波后的值 */
    private var accelX = 0.0
    private var accelY = 0.0
    private var gravityValid = false
    /** 上次"甩手机"触发的时间（毫秒）：触发后 [SHAKE_COOLDOWN_MS] 内不再触发，避免一次甩动连发 */
    private var lastShakeMs = 0L
    /** 慢速重力估计（m/s²，屏幕坐标）：只用于算"甩动方向"，别拿它当重力用 */
    private var gravSlowX = 0.0
    private var gravSlowY = 0.0
    /** 重力唤醒的连续命中计数（防抖，见 moveTicker） */
    private var gravityWakeHits = 0
    /** 上一次"停稳"的时间（毫秒，uptime）：落地后短暂不响应重力唤醒 */
    private var lastRestMs = 0L
    /** 本次抛掷已经推进了多少帧（只用于结束时的 fps 日志，排查"弹跳卡卡的"） */
    private var throwFrames = 0
    /** 本次抛掷撞墙次数（只用于日志） */
    private var throwBounces = 0

    /** 临时诊断开关：逐帧打抛掷轨迹（定位"闪到很远"用，定位完改回 false） */
    private val throwTrace = false

    private var throwStartMs = 0L
    private var lastMoveMs = 0L

    // ---- 拖动手感的性能优化 ----
    /**
     * 屏幕尺寸缓存。
     *
     * `screenPx()` / `navBarBottomInset()` 内部是 `WindowManager.currentWindowMetrics`（**binder 调用**）。
     * 拖动时每个 MOVE 事件都会算一次可用范围，120Hz 屏上就是每秒 200+ 次跨进程查询，
     * 主线程直接被这点开销拖出掉帧。屏幕尺寸只在旋转/分屏/折叠态变化时才会变，
     * 所以缓存起来 + 在 `onConfigurationChanged` 里刷新即可。
     */
    private var cachedScreenW = 0
    private var cachedScreenH = 0
    private var cachedNavInset = -1

    /** 每帧最多提交一次窗口位置（见 [moveWindow]） */
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    /** 最近一次 ACTION_MOVE 的事件时间：用来算"触摸→窗口移动"的延迟 */
    private var lastTouchEventTime = 0L
    private var pendingWinX = 0
    private var pendingWinY = 0
    private var moveFrameScheduled = false

    /**
     * 把 [pendingWinX]/[pendingWinY] 真正写进窗口参数。
     *
     * ⚠️ 别在这里做"实测 getLocationOnScreen 再补偿"：悬浮窗在挂载/更新瞬间
     * `getLocationOnScreen` 可能返回离谱的值（实测拿到过 -600 万），
     * 一旦当成偏移量写进 `lp.x/lp.y`，窗口会直接被搬到屏幕外（桌宠"消失"）。
     * 解决"状态栏让 lp.y 多加一截"用的是 `FLAG_LAYOUT_IN_SCREEN` + `fitInsetsTypes = 0`，
     * 真机验证 `mAttrs` 与 `frame` 完全一致，不需要运行时补偿。
     */
    private fun applyWindowPosition() {
        moveFrameScheduled = false
        val lp = params ?: return
        val c = container ?: return
        if (lp.x == pendingWinX && lp.y == pendingWinY) return
        lp.x = pendingWinX
        lp.y = pendingWinY
        val t = SystemClock.uptimeMillis()
        runCatching { wm.updateViewLayout(c, lp) }
        // 触摸窗口跟着视觉窗口走（位置 = 视觉窗口 + 人物包围盒左上角）
        syncTouchWindow()
        val cost = SystemClock.uptimeMillis() - t
        if (cost >= 8) android.util.Log.i("Dsh_JANK", "updateViewLayout ${cost}ms → $pendingWinX,$pendingWinY")
        // 拖拽"跟手度"诊断：手指 ACTION_MOVE → 窗口真正搬过去的时间差。
        // 正常应在 1 帧内（≤20ms）；超过 30ms 记一条，"不跟手"时直接看这个数。
        if (dragging && lastTouchEventTime > 0) {
            val lag = (t - lastTouchEventTime).toInt()
            if (lag > 30) android.util.Log.i("Dsh_TOUCHLAG", "拖拽延迟 ${lag}ms（窗口 ${lp.width}x${lp.height}）")
        }
    }

    private val applyPendingMove = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) = applyWindowPosition()
    }

    // ---- 运动帧循环（抛掷物理 + 散步位移）----
    // 以前是 33ms 的 `moveTicker`（≈30fps）：抛掷/反弹、散步都是一跳一跳地走，高刷屏上特别明显；
    // 而且 `tickPhysics` 里写死 `dt = 0.016`，跟 33ms 的调度对不上 → 抛掷实际只有一半速度。
    // 现在按 vsync 驱动（Choreographer），dt 用**真实帧间隔**（60/90/120Hz 各自正确）。
    private var motionScheduled = false
    /** 循环是否还在连续推进；false 表示已停下，下次起步要重置时间基准 */
    private var motionAlive = false
    private var lastMotionNs = 0L

    private val motionFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            motionScheduled = false
            val prev = lastMotionNs
            lastMotionNs = frameTimeNanos
            // 首帧给一个 60fps 的默认步长；长卡顿（>40ms）夹住，别让桌宠瞬移
            val dt = if (prev == 0L) 1.0 / 60.0
            else ((frameTimeNanos - prev) / 1_000_000_000.0).coerceIn(0.004, 0.040)
            if (prev != 0L && dt >= 0.030) {
                // 掉帧诊断：切应用/切页面时如果出现这条，说明是系统调度或主线程被占
                android.util.Log.i("Dsh_SVC", "运动帧间隔 ${(dt * 1000).toInt()}ms（掉帧）")
            }
            if (physMode != null) stepPhysics(dt)
            if (engine.movePlanActive()) engine.tickMove()   // 内部按墙钟算进度，帧率无关
            if (physMode != null || engine.movePlanActive()) {
                scheduleMotion()
            } else {
                motionAlive = false
            }
        }
    }

    private fun scheduleMotion() {
        if (motionScheduled) return
        motionScheduled = true
        // 只在"从静止重新起步"时清时间基准，否则连续运行时每帧都会被当成首帧
        // （那样 90/120Hz 屏上会按 60fps 的步长推进 → 慢放）
        if (!motionAlive) {
            lastMotionNs = 0L
            motionAlive = true
        }
        choreographer.postFrameCallback(motionFrame)
    }

    // ---- 定时器 ----
    // 永续调度（空闲时只做空检查，开销可忽略）。只负责"发现需要运动/需要推进"，
    // 真正的逐帧推进在 [motionFrame] 里；测试动画间隔的 tickGap 也在这里。
    private val moveTicker = object : Runnable {
        override fun run() {
            // 设置页在前台时不新开散步（见 uiForeground 注释），动画本身不受影响
            if (!uiForeground) engine.tickGap()
            // 重力感应：静止时也要"活"过来 —— 手机一歪，桌宠得自己往新的下方滑/掉。
            // 灵敏度权衡：太灵敏会在换动画时被窗口尺寸差误触发（规律性掉帧），太迟钝就是
            // "手机都竖过来了它还不掉"（用户反馈"普通场景下太慢了"）。现在的取值：
            //   容差 16px + 连续 2 次确认（≈66ms）+ 落地后 200ms 冷却。
            // **用户正在按住/拖动时不唤醒**：那时窗口由手指 1:1 驱动，物理再插一脚就会"闪跳"。
            if (curGravitySensor && gravityValid && physMode == null && !dragging && !pressActive &&
                android.os.SystemClock.uptimeMillis() - lastRestMs > GRAVITY_WAKE_COOLDOWN_MS
            ) {
                if (isRestingOnGravitySide()) {
                    gravityWakeHits = 0
                } else if (++gravityWakeHits >= GRAVITY_WAKE_HITS) {
                    gravityWakeHits = 0
                    physPos = doubleArrayOf(engine.winX.toDouble(), engine.winY.toDouble())
                    physVel = doubleArrayOf(0.0, 0.0)
                    beginThrow()
                }
            }
            if ((engine.movePlanActive() || physMode != null) && !motionScheduled) scheduleMotion()
            uiHandler.postDelayed(this, 33)
        }
    }

    // ================================================================ 生命周期
    /** 服务创建：只做轻量初始化（配置引用、屏幕密度）。窗口要等 `onStartCommand` 里权限校验通过再建。 */
    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("DshSvc", "onCreate instance=$instanceId")
        AppLog.u("桌宠", "悬浮窗服务已创建（实例 $instanceId）")
        config = PetConfig.get(this)
        density = resources.displayMetrics.density
        refreshScreenCache()          // 拖动热路径用缓存值，这里先把屏幕尺寸/导航条量好
        AppLog.log("SVC", "onCreate instance=$instanceId 屏幕 ${cachedScreenW}x$cachedScreenH 导航条=$cachedNavInset")
    }

    /**
     * 进入前台，并**按需带上 microphone 类型**。
     *
     * 为什么必须带：按 Android 的"使用时授权"（while-in-use）规则，应用在后台时只有
     * 「声明了 microphone 类型的前台服务」才允许访问麦克风。桌宠悬浮在别的应用之上时
     * 我们没有可见 Activity——少了这个类型，SpeechRecognizer 会反复 ERROR_AUDIO/ERROR_CLIENT，
     * 听感就是"收音断断续续"。
     *
     * Android 14 起从后台（例如开机自启）启动该类型会抛异常，所以失败就退回纯 specialUse。
     */
    private fun enterForeground() {
        val id = 1000 + instanceId
        val noti = buildNotification()
        val withMic = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    id,
                    noti,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(id, noti)
            }
            true
        }.getOrElse { e ->
            AppLog.log("SVC", "前台服务(含麦克风类型)失败，退回 specialUse: ${e.message}")
            false
        }
        if (withMic) return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    id,
                    noti,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(id, noti)
            }
        }.onFailure { AppLog.log("SVC", "退回 specialUse 也失败: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i("DshSvc", "onStartCommand action=${intent?.action} startId=$startId nullIntent=${intent == null}")
        val extraId = intent?.getIntExtra(EXTRA_INSTANCE, -1) ?: -1
        instanceId = if (extraId >= 0) extraId else defaultInstanceId
        AppLog.log(
            "SVC",
            "onStartCommand instance=$instanceId action=${intent?.action} startId=$startId " +
                    "重启=${intent == null}(null intent=系统重建) container=${container != null}"
        )
        synchronized(activeInstances) { activeInstances.add(instanceId) }
        when (intent?.action) {
            "quit" -> { quit(); return START_NOT_STICKY }
            "hide" -> { setHidden(true); return START_NOT_STICKY }
            "show" -> { setHidden(false); return START_NOT_STICKY }
            "toggle_island" -> { toggleIsland(); return START_NOT_STICKY }
            ACTION_PLAY -> {
                // 空名字 = 退出手动循环，回到随机链；否则循环播放这一段（测试台用）
                val name = intent?.getStringExtra(EXTRA_ANIM).orEmpty()
                intent?.getFloatExtra(EXTRA_SPEED, 0f)?.takeIf { it > 0f }?.let { playSpeedOverride = it }
                if (container != null) {
                    runCatching {
                        if (name.isBlank()) engine.clearManual() else engine.playManual(name)
                    }.onFailure { AppLog.log("SVC", "播放动画失败 $name: ${it.message}") }
                } else {
                    // 服务刚被 startService 拉起来：窗口还没建好，先记下来，initPet 末尾补播
                    pendingPlayAnim = name
                    AppLog.log("SVC", "动画指令暂存（窗口未就绪）：$name")
                }
                return START_NOT_STICKY
            }
            ACTION_PLAY_SPEED -> {
                // 只调速度，不打断当前循环
                val sp = intent?.getFloatExtra(EXTRA_SPEED, 1f) ?: 1f
                playSpeedOverride = sp.coerceIn(0.2f, 3f)
                if (container != null) {
                    runCatching { videoView.setPlaybackSpeed(playSpeedOverride!!) }
                }
                AppLog.log("SVC", "测试台播放速度 = $playSpeedOverride")
                return START_NOT_STICKY
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch { config.setPetRunning(true) }
        wantRunning = true
        // 挂上"15 分钟自愈看门狗"：HyperOS/MIUI 会把前台服务也清掉，靠它兜底重新拉起
        runCatching { PetWatchdogJob.schedule(this) }
        enterForeground()
        if (synchronized(initFailed) { initFailed.contains(instanceId) }) {
            // 上次初始化失败：不再重试，避免重启循环
            stopSelf()
            return START_NOT_STICKY
        }
        // 防重入：initPet() 是 suspend 的，`container` 要到 buildWindow() 末尾才赋值。
        // 如果两发 startService 挨得很近（开机广播 + 应用启动、START_STICKY 重建 + 用户点击…），
        // 两次 onStartCommand 都会看到 container == null → 初始化两遍 → **两个悬浮窗**：
        // 多出来的那个永远停在 (0,0)、看不见，却仍然吃触摸（左上角莫名其妙点不到东西）。
        if (container == null && !initStarted) {
            initStarted = true
            scope.launch {
                try {
                    initPet()
                } catch (e: Throwable) {
                    AppLog.log(
                        "INIT",
                        "初始化失败: ${e.javaClass.simpleName}: ${e.message}\n${android.util.Log.getStackTraceString(e)}"
                    )
                    val rt = Runtime.getRuntime()
                    AppLog.log(
                        "INIT",
                        "失败时内存: 堆已用${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB/上限${rt.maxMemory() / 1048576}MB"
                    )
                    synchronized(initFailed) { initFailed.add(instanceId) }
                    showBubble("桌宠初始化失败：${e.message}")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    /** 服务销毁：停语音 → 关窗口/菜单/气泡 → 卸配置监听 → 退出碰撞体 → 清静态实例表，保证下次能干净重建。 */
    override fun onDestroy() {
        runCatching { if (::taps.isInitialized) taps.release() }
        runCatching { stopVoiceInOverlay() }
        AppLog.log(
            "SVC",
            "onDestroy instance=$instanceId（用户退出/服务被杀/初始化失败均会到这）"
        )
        synchronized(activeInstances) { activeInstances.remove(instanceId) }
        scope.cancel()
        uiHandler.removeCallbacksAndMessages(null)
        settingsJobs.forEach { it.cancel() }
        savePosition()
        removeMenuNow()
        bubble?.dismiss()
        stopIsland()
        stopQuickChat()
        collisionMember?.let { CollisionHub.unregister(it.id) }
        collisionMember = null
        CollisionHub.setOnMoved(instanceId, null)
        stopAgentBus()
        dismissChat()
        easterEggs.forEach { it.dismiss() }
        easterEggs.clear()
        container?.let { runCatching { wm.removeView(it) } }
        touchView?.let { runCatching { wm.removeView(it) } }
        touchView = null
        touchParams = null
        runCatching { choreographer.removeFrameCallback(applyPendingMove) }   // 别让帧回调打到已移除的窗口上
        runCatching { choreographer.removeFrameCallback(motionFrame) }        // 同上：运动帧循环也要停
        runCatching { setGravitySensor(false) }                              // 注销加速度计
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) }
        physMode = null
        motionAlive = false
        container = null
        runCatching { videoView.release() }
        soundPool?.release()
        CoroutineScope(Dispatchers.Main).launch {
            // 只在"用户主动退出"（wantRunning=false）时清标记。
            // 系统杀服务 / 划掉最近任务时不清：否则看门狗会以为用户不要了，再也不拉起。
            if (activeInstanceIds().isEmpty() && !wantRunning) config.setPetRunning(false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================================================================ 初始化
    /**
     * 把一只桌宠真正"搭"起来（只跑一次）：
     * 读配置 → 算窗口尺寸 → 建素材目录 [PetCatalog] / 状态机 [PetEngine] / 音效
     * → 订阅设置 → 建窗口与手势 → 起灵动岛/Agent 总线/自言自语。
     */
    private suspend fun initPet() {
        fun mem(): String {
            val rt = Runtime.getRuntime()
            return "堆${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB"
        }
        android.util.Log.i("DshSvc", "initPet start")
        val t0 = android.os.SystemClock.elapsedRealtime()   // 启动耗时打点（下面的日志都减它）
        AppLog.log("SVC", "initPet start instance=$instanceId build=${com.dshpet.android.BuildConfig.VERSION_NAME}/${com.dshpet.android.BuildConfig.BUILD_STAMP} ${mem()}")
        // 适配决策只落这一行：机型差异全部收敛在 com.dshpet.android.compat 里
        AppLog.log("COMPAT", platformFacts.logLine(windowPlan))
        stateStore = PetState(this, instanceId)
        catalog = PetCatalog.load(this)
        android.util.Log.i("DshSvc", "catalog loaded")
        AppLog.log("SVC", "catalog 加载 ${catalog.names.size} 段动画 ${android.os.SystemClock.elapsedRealtime() - t0}ms ${mem()}")
        engine = PetEngine(
            catalog = catalog,
            play = { name -> playAnimation(name) },
            moveTo = { x, y -> moveWindow(x, y) },
            onFacingChanged = { f -> videoView.setMirror(engine.shouldMirror(engine.anim ?: "")); persistFacing(f) },
            onMoveFinished = { savePosition() },
        ).apply {
            noMove = curNoMove
            animationGapSeconds = curGap
            currentPositionSecProvider = { videoView.currentPositionMs() / 1000.0 }
        }

        // 缩放档位与描边开关（窗口几何由 PetWindowFactory 按动画包围盒现算）
        curScale = config.scale()
        curBorder = config.windowBorder()

        android.util.Log.i("DshSvc", "about to buildWindow")
        buildWindow()
        // 建窗后再走一次统一的几何入口：保证"启动"和"改档位/换动画"用的是同一段代码
        applyScale(reposition = false)

        val seq: TapSequencer = TapSequencer(
            listener = object : TapSequencer.Listener {
                override fun onTapCommitted(count: Int) { this@PetOverlayService.onTapCommitted(count) }
            },
            slop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
        )
        taps = seq

        // 手势
        installGestures()

        // 音效
        setupSound()

        // 恢复朝向；**位置固定用屏幕正中**（不再恢复上次漫游到的坐标）
        val st = stateStore!!.load()
        // 默认落点按**人物**算，并且要在导航条之上（见 restPosition 的注释）
        val (defaultX, defaultY) = restPosition()
        engine.setFacing(st.facing.ifEmpty { "left" })
        videoView.setMirror(engine.shouldMirror(engine.anim ?: ""))
        // 多开错位：新实例向右上偏移，避免与母体重叠
        val offsetX = if (instanceId > 0) (instanceId * 48).coerceAtMost(engine.winW / 2) else 0
        val offsetY = if (instanceId > 0) (instanceId * 32).coerceAtMost(engine.winH / 2) else 0
        val xr = windowXRange(); val yr = windowYRange()
        // 垂直上限：人物的脚不能低于"导航条上沿"，否则整个身子被手势条挡住（"出生时几乎看不到"）
        // 垂直上限：人物的脚不能低于"导航条上沿"，否则整个身子被手势条挡住（"出生时几乎看不到"）。
        // 按**人物像素**算：脚底 = 窗口底 - insetB。
        val maxVisibleY = usableBottom() - (8 * density).toInt() - engine.winH + insetB
        val yrTop = maxOf(yr.first, minOf(yr.last, maxVisibleY))
        engine.setPosition(
            (defaultX - offsetX).coerceIn(xr.first, xr.last),
            (defaultY - offsetY).coerceIn(yr.first, yrTop),
        )
        syncEngineScreenBounds()
        AppLog.log("SVC", "窗口与手势就绪 ${android.os.SystemClock.elapsedRealtime() - t0}ms")

        // 气泡
        bubble = SpeechBubble(this, engine, config)
        bubble?.bubbleStyle = config.selfTalkBubbleStyle()
        curBubbleColor = config.selfTalkBubbleColor()
        bubble?.bubbleColor = curBubbleColor
        bubble?.blurOn = config.blurEnabled() && Build.VERSION.SDK_INT >= 31

        // 播放速度（默认 1.5x）
        curSpeed = config.playbackSpeed()

        // 碰撞物理（默认开）
        curCollision = config.collisionEnabled()
        // 音效音量（上游 v4.0.5：0-100%）
        curSoundVolume = config.soundVolume() / 100f
        // 点击台词绑定（上游 v4.1.0）
        curClickTalk = config.clickTalk()
        curThrowStrength = config.throwStrength()
    // 启动时把影响手感的开关打一行（排障用：拖动物理关了就没有抛掷，"甩不出去"先看这里）
    AppLog.log(
        "SVC",
        "手感开关：拖动物理=$curPhysics 重力感应=$curGravitySensor 甩出力度=$curThrowStrength 锁定位置=$curLock",
    )

        // 行为参数：移动概率/散步距离（设置可调）
        val moveProb = config.moveProbability()
        engine.pActs = (1.0 - moveProb).coerceIn(0.1, 0.95)
        engine.moveMinPx = config.moveMinPx()
        engine.moveMaxPx = config.moveMaxPx()

        // 自言自语素材缓存（供非协程回调使用）
        curSelfTalkTexts = config.selfTalkTexts().toList()
        curSelfTalkDurationMs = (config.selfTalkDuration() * 1000).toLong()
        curSelfTalk = config.selfTalkEnabled()
        curSelfTalkMinSec = config.selfTalkMin().coerceAtLeast(5)
        curSelfTalkMaxSec = config.selfTalkMax().coerceAtLeast(curSelfTalkMinSec)
        curSelfTalkAi = config.selfTalkAi()
        curSelfTalkAiVoice = config.selfTalkAiVoice()

        // 设置监听（改动即时生效）
        observeSettings()

        // 启动动画链
        engine.start()
        uiHandler.postDelayed(moveTicker, 33)

        // 迟到的「播放指定动画」指令（startService 时 onCreate/initPet 还没跑完，
        // onStartCommand 里的 container 还是 null，先存下来在这里补执行）
        pendingPlayAnim?.let { name ->
            pendingPlayAnim = null
            runCatching {
                if (name.isBlank()) engine.clearManual() else engine.playManual(name)
            }.onFailure { AppLog.log("SVC", "补播 $name 失败: ${it.message}") }
        }

        // Agent 联动插件总线（上游统一事件协议；默认关，设置开启）
        if (config.agentLinkEnabled()) startAgentBus()

        // 灵动岛（设置开启则随主实例启动；统一入口，防 watch 双重创建）
        if (instanceId == 0 && config.islandEnabled()) {
            ensureIsland()
        }

        // 多开碰撞物理（上游"鱼塘碰碰车"；默认开）
        collisionMember = CollisionHub.Member(
            id = instanceId,
            x = engine.winX.toDouble(), y = engine.winY.toDouble(),
            w = engine.winW, h = engine.winH,
        ).also { m ->
            CollisionHub.setEnabled(curCollision)
            CollisionHub.register(m)
            // 碰撞结算写回：本实例被撞 → 更新窗口/被撞飞进入抛掷
            CollisionHub.setOnMoved(instanceId) { member ->
                uiHandler.post {
                    if (dragging) return@post
                    val speed = kotlin.math.hypot(member.vx, member.vy)
                    if (physMode == null) {
                        if (speed > 60) {
                            // 静止的鱼被撞飞
                            physPos = doubleArrayOf(member.x, member.y)
                            physVel = doubleArrayOf(member.vx, member.vy)
                            beginThrow()
                        } else {
                            moveWindow(member.x.roundToInt(), member.y.roundToInt())
                            savePosition()
                        }
                    } else {
                        // 抛掷中的鱼：采纳碰撞后的速度
                        physVel = doubleArrayOf(member.vx, member.vy)
                    }
                }
            }
        }
        scheduleSelfTalk(firstRun = true)
        AppLog.log("SELFTALK", "initPet: 开关=$curSelfTalk 素材=${curSelfTalkTexts.size} 条 时长=${curSelfTalkDurationMs}ms")
        applyOpacity()
        AppLog.log(
            "SVC",
            "initPet 完成 ${android.os.SystemClock.elapsedRealtime() - t0}ms，窗口 ${engine.winX},${engine.winY} ${engine.winW}x${engine.winH} ${mem()}"
        )
    }

    private var collisionMember: CollisionHub.Member? = null
    private var island: DynamicIsland? = null
    private var quickChat: QuickChat? = null

    /** 双击间隔（毫秒）内二连击 → 快速对话 */
    private var lastTapMs = 0L

    // ---- 弹弓弹射（上游 PR#33）----
    private var slingshotAiming = false
    /** 蓄力那根手指的 pointerId（-1 = 空闲）。ACTION_POINTER_DOWN 的 rawX 是**第一根手指**的坐标，不能用 */
    private var slingshotPointerId = -1
    /** 蓄力锚点 = 按下第二根手指时桌宠的窗口中心（raw 坐标） */
    private var slingshotAnchorX = 0f
    private var slingshotAnchorY = 0f
    /** 当前拉距（手指相对锚点的位移，发射方向与它相反） */
    private var slingshotPullX = 0f
    private var slingshotPullY = 0f
    private var curThrowStrength = "standard"
    internal var curClickTalk = ""

    /** 桌宠显示/隐藏（灵动岛单击；仅主实例处理） */
    private fun setHidden(hidden: Boolean) {
        if (instanceId != 0) return
        petHidden = hidden
        val c = container
        if (c != null) {
            uiHandler.post { c.visibility = if (hidden) View.GONE else View.VISIBLE }
            if (hidden) videoView.pausePlay() else videoView.resumePlay()
            AppLog.log("SVC", "桌宠 ${if (hidden) "隐藏" else "显示"}")
        }
    }

    /** 菜单入口：切换（显示↔隐藏） */
    fun toggleIsland() {
        if (instanceId != 0) return
        AppLog.log("ISLAND", "toggleIsland: isShowing=${island?.isShowing}")
        if (island?.isShowing == true) {
            island?.dismiss()
        } else {
            ensureIsland()
        }
    }

    /** 显示灵动岛：唯一创建入口（已有实例先关再复用，绝不多窗口） */
    private fun ensureIsland() {
        if (island?.isShowing == true) return
        // 关掉可能的孤儿实例（上次未正常 dismiss 的）
        island?.dismiss()
        island = DynamicIsland(this).also { it.show() }
    }

    private fun stopIsland() {
        AppLog.log("ISLAND", "stopIsland")
        island?.dismiss()
        island = null
    }

    private fun toggleQuickChat() {
        if (quickChat?.isShowing() == true) {
            quickChat?.dismiss()
        } else {
            if (quickChat == null) quickChat = QuickChat(this)
            quickChat?.show(engine.winX + engine.winW / 2, engine.winY)
        }
    }

    private fun stopQuickChat() {
        quickChat?.dismiss()
        quickChat = null
    }

    // ================================================================ Agent 联动（插件总线）
    private var agentBus: com.dshpet.android.plugin.AgentEventBus? = null

    private fun playCollisionSound() {
        if (curClickSound) playClickSound()
    }

    private fun startAgentBus() {
        if (agentBus != null) return
        agentBus = com.dshpet.android.plugin.AgentEventBus(this) { agent, state ->
            onAgentState(agent, state)
        }.also { it.start() }
    }

    private fun stopAgentBus() {
        agentBus?.stop()
        agentBus = null
    }

    /** 六态 → 动画/气泡（上游 agent_link_presentation 同款映射） */
    private fun onAgentState(agent: String, state: String) {
        AppLog.log("PLUGIN", "Agent[$agent] 状态: $state")
        when (state) {
            "thinking", "working" -> {
                // 联动动作池轮换（写代码/敲击为主）
                val pool = listOf("写代码", "原地敲击桌面互动", "吃Token", "深度思考碎碎念")
                    .filter { engine.hasAnim(it) }
                if (pool.isNotEmpty()) {
                    engine.switch(pool[kotlin.random.Random.nextInt(pool.size)])
                }
            }
            "attention" -> showBubble("主人，Agent（$agent）这边需要你看一眼～", 6000)
            "error" -> showBubble("Agent（$agent）执行好像遇到报错了…", 6000)
            "idle", "sleeping" -> engine.switchToIdle()
        }
    }

    // ================================================================ 自言自语
    /**
     * 排下一次"自说自话"。
     *
     * @param firstRun true = 刚启动/刚打开开关：**8~15 秒**内先说一句，
     *   否则要等设置的 20~60s，用户会以为"这个功能根本没实现"（上一轮就是这么被报的）。
     *   之后恢复正常随机区间，避免一直叨叨。
     */
    private fun scheduleSelfTalk(firstRun: Boolean = false) {
        selfTalkRunnable?.let { uiHandler.removeCallbacks(it) }
        selfTalkRunnable = null
        if (!curSelfTalk) {
            AppLog.log("SELFTALK", "未开启，不排期")
            return
        }
        val min = curSelfTalkMinSec.coerceAtLeast(5)
        val max = curSelfTalkMaxSec.coerceAtLeast(min)
        val delaySec = if (firstRun) {
            FIRST_TALK_MIN_SEC + kotlin.random.Random.nextDouble() * (FIRST_TALK_MAX_SEC - FIRST_TALK_MIN_SEC)
        } else {
            min + kotlin.random.Random.nextDouble() * (max - min)
        }
        val r = Runnable {
            showRandomSelfTalk()
            scheduleSelfTalk()
        }
        selfTalkRunnable = r
        uiHandler.postDelayed(r, (delaySec * 1000).toLong())
        AppLog.log("SELFTALK", "排期 ${(delaySec * 1000).toLong()}ms 后（首次=$firstRun 区间=$min~${max}s）")
    }

    private fun showRandomSelfTalk() {
        // 开了「AI 生成台词」就走模型（没 Key / 失败自动回退到内置台词，不让桌宠变哑巴）
        if (curSelfTalkAi) {
            // 连点防抖：一次请求还没回来就别再发第二个（否则连点会刷出一串请求）
            if (selfTalkAiBusy) return
            selfTalkAiBusy = true
            scope.launch {
                try {
                    val text = com.dshpet.android.voice.core.SelfTalkAi.generate(this@PetOverlayService)
                    if (text == null) showCannedSelfTalk() else showSelfTalkText(text)
                } finally {
                    selfTalkAiBusy = false
                }
            }
            return
        }
        showCannedSelfTalk()
    }

    private fun showCannedSelfTalk() {
        if (curSelfTalkTexts.isEmpty()) {
            AppLog.log("SELFTALK", "素材为空，跳过（设置 → 外观 → 自言自语文案）")
            return
        }
        val text = curSelfTalkTexts[kotlin.random.Random.nextInt(curSelfTalkTexts.size)]
        showSelfTalkText(text)
    }

    /** 真正把一句话冒出来（+ 可选语音）。AI 版和内置版共用这一处 */
    private fun showSelfTalkText(text: String) {
        val dur = curSelfTalkDurationMs.coerceIn(1000, 30000)
        bubble?.showText(text, durationMs = dur)
        // 用户日志留痕：验证"气泡到底出没出"只看这一行
        AppLog.u("桌宠", "自说自话：$text")
        AppLog.log("SELFTALK", "显示 ${dur}ms（AI=$curSelfTalkAi 语音=$curSelfTalkAiVoice）：$text")
        // 语音对话正在跑的时候不插嘴（麦克风/音频焦点都在那边）
        if (curSelfTalkAiVoice && !com.dshpet.android.voice.core.VoiceLoop.running) {
            scope.launch {
                com.dshpet.android.voice.core.QuickTts.speak(this@PetOverlayService, text)
            }
        }
    }

    /** 触摸区域只打一次日志（每次动画都打会刷屏） */
    private var touchRegionLogged = false

    /**
     * 能不能用 `touchableRegion` 把窗口的触摸范围收窄到人物像素？
     *
     * `ViewTreeObserver.InternalInsetsInfo` 是 @hide；Android 12+ 的 hidden API 限制会**直接 deny**
     * 反射读取它（真机日志：`Accessing hidden field ...InternalInsetsInfo;->touchableRegion:Region
     * (domain=platform, api=max-target-r) ... denied`，HyperOS / Android 16 上就是这样）。
     *
     * 探测失败时必须**不要**用"所有动画的并集"当窗口：那样窗口会很接近全屏，
     * 而收窄又用不了 → 透明区把整屏点击全吃掉（"点哪都没反应"）。这时退回按动画包围盒的小窗口。
     */
    private val touchRegionSupported: Boolean by lazy {
        runCatching {
            val c = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val field = c.getField("touchableRegion")
            val ctor = c.getDeclaredConstructor().apply { isAccessible = true }
            field.get(ctor.newInstance())
            true
        }.getOrElse { e ->
            AppLog.log(
                "SVC",
                "触摸区域不可用（hidden API 受限：${e.javaClass.simpleName}）→ 窗口退回按动画包围盒",
            )
            false
        }
    }

    /**
     * 把悬浮窗的**可触摸区域**收窄到人物像素（`touchableRegion`）。
     *
     * 背景：为了让"换动画"不再改窗口尺寸（那是掉帧根源），窗口固定成所有动画的并集，
     * 里面必然有一大片透明区。不收窄的话，那块透明区会把底下应用的点击吃掉。
     *
     * ⚠️ 2026-10-26 踩过的坑：第一版写成"`view.width/height` 为 0 就跳过设置"，
     * 结果回调第一次触发时视图还没测量 → 区域一直是**空的**（`touchable region=SkRegion()`），
     * 整个悬浮窗**任何触摸都收不到**（用户表现：完全拖不动）。
     * 现在：宽高用 `params`（窗口参数，建窗后就知道）兜底；算出来的矩形不合法时**退回整窗**，
     * 保证永远不会出现"空区域"。
     *
     * `ViewTreeObserver.OnComputeInternalInsetsListener` / `InternalInsetsInfo` 都是 @hide API，
     * 只能反射：失败就只打日志（退化成一个整窗可触摸的悬浮窗），不影响其它功能。
     */
    private fun installTouchRegion(view: android.view.View) {
        runCatching {
            val listenerCls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerCls.classLoader,
                arrayOf(listenerCls),
            ) { _, method, args ->
                if (method.name == "onComputeInternalInsets" && !args.isNullOrEmpty()) {
                    runCatching {
                        val info = args[0]
                        val ic = info.javaClass
                        val lp = params
                        // 宽高：视图已测量就用视图的，否则用窗口参数（建窗时就确定了），最后才用屏幕
                        val w = view.width.takeIf { it > 0 } ?: lp?.width?.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels
                        val h = view.height.takeIf { it > 0 } ?: lp?.height?.takeIf { it > 0 }
                        ?: resources.displayMetrics.heightPixels

                        (ic.getField("contentInsets").get(info) as? android.graphics.Rect)?.setEmpty()
                        (ic.getField("visibleInsets").get(info) as? android.graphics.Rect)?.setEmpty()
                        // TOUCHABLE_INSETS_REGION = 3
                        ic.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                            .invoke(info, 3)
                        val region = ic.getField("touchableRegion").get(info) as android.graphics.Region

                        val l = insetL.coerceIn(0, w)
                        val t = insetT.coerceIn(0, h)
                        val r = (w - insetR).coerceIn(0, w)
                        val b = (h - insetB).coerceIn(0, h)
                        if (r - l >= MIN_TOUCH_REGION_PX && b - t >= MIN_TOUCH_REGION_PX) {
                            region.set(l, t, r, b)
                        } else {
                            // 兜底：宁可整窗可触摸，也不能变成"什么都点不到"
                            region.set(0, 0, w, h)
                        }
                        if (!touchRegionLogged) {
                            touchRegionLogged = true
                            android.util.Log.i("Dsh_SVC", "触摸区域 = ($l,$t)-($r,$b) window=${w}x$h")
                        }
                    }
                }
                null
            }
            android.view.ViewTreeObserver::class.java
                .getMethod("addOnComputeInternalInsetsListener", listenerCls)
                .invoke(view.viewTreeObserver, proxy)
            android.util.Log.i("Dsh_SVC", "触摸区域已收窄到人物像素（touchableRegion）")
        }.onFailure { e ->
            android.util.Log.i(
                "Dsh_SVC",
                "触摸区域收窄失败：${e.javaClass.simpleName} ${e.message}（窗口会整块吃触摸）",
            )
        }
    }

    /**
     * 建悬浮窗本身：`FrameLayout` 里自下而上叠 **视频层**（[PetVideoView]，按 16:9 画布摆放后
     * 横向偏移，只露出人物所在的那条）和 **波纹层**（[PetVoiceWaveView]，铺满整窗、自己算圆心）。
     * 末尾挂一个 260ms 的 `waveTick`：既推进波纹动画，也顺带做触摸门控的轮询。
     */
    private fun buildWindow() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // 初始几何：先用待机动画的包围盒，engine.start() 后会按实际播放的动画再调
        val g0 = geometryFor(PetWindowFactory.IDLE_ANIM)
        engine.setWindowSize(g0.winW, g0.winH)
        val w = g0.winW
        val h = g0.winH
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // LAYOUT_IN_SCREEN：坐标按**整块屏幕**算（否则 status bar 会让窗口的 parent frame
            // 从状态栏下沿开始，lp.y 被系统再加一截 → 桌宠比预期低 121px，"掉出屏幕"）。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "dsh-pet-$instanceId"
            // **关键**：不让窗口被状态栏/导航条"让位"。
            // 默认 fitTypes=STATUS_BARS|NAVIGATION_BARS 时，窗口的"容器矩形"从状态栏下沿开始
            // （这台机器是 y=146），于是 `lp.y` 会被系统 **再加 146** 才是真实屏幕位置：
            // 我们按"脚底贴屏幕下沿"算好的 y，落到屏幕上就低了 146px ——
            // 这就是"桌宠落到下沿时一半身子在屏幕外"的根因（小尺寸时更明显）。
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }
        params = lp
        if (windowPlan.splitTouchWindow) {
            // 收不到 touchableRegion 的系统：视觉窗口不接手势，手势交给下面的小触摸窗口
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        android.util.Log.i(
            "DshPetWin",
            "buildWindow size=${w}x${h} screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}"
        )

        // 纯代码创建视图（避免 XML inflate 自定义 View 的兼容性问题）
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        // 窗口尺寸固定成"所有动画的并集"（换动画不再 resize → 不再掉帧），
        // 代价是窗口里有一大片透明区。用 touchableRegion 把可触摸范围收窄到**人物像素**，
        // 否则那块透明区会吃掉底下应用的点击。
        if (!windowPlan.splitTouchWindow) installTouchRegion(root)
        videoView = PetVideoView(this)
        // 先占位；画布尺寸与偏移统一由 applyGeometry() 设置（改档位/换动画都走它）
        root.addView(videoView)
        applyGeometry(g0)
        android.util.Log.i("DshPetWin", "visible=${g0.winW}x${g0.winH} canvas=${g0.canvasW}x${g0.canvasH}")

        waveView = PetVoiceWaveView(this).also { wv ->
            // 铺满整个窗口：波纹的圆心/半径由 View 自己按窗口尺寸算（见 PetVoiceWaveView）
            root.addView(
                wv,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            wv.isClickable = false
            wv.isFocusable = false
        }
        val waveTick = object : Runnable {
            override fun run() {
                val l = voiceLoop
                // 只按"语音真的在跑"点亮：服务端 stop() 之后 running 变 false，波纹要跟着灭
                val running = l != null && com.dshpet.android.voice.core.VoiceLoop.running
                // 光环要整圈可见 → 状态翻转时重算几何（头顶多留/收回一块），人物位置不变
                if (running != curHaloActive) {
                    curHaloActive = running
                    runCatching {
                        applyGeometry(geometryFor(engine.anim), keepAnchor = true)
                    }.onFailure { AppLog.log("SVC", "光环几何切换失败：${it.message}") }
                    AppLog.log("SVC", "语音光环 ${if (running) "开启" else "关闭"} win=${engine.winW}x${engine.winH}")
                }
                waveView?.setActive(
                    running,
                    l?.state?.value is com.dshpet.android.voice.core.VoiceState.Speaking,
                )
                uiHandler.postDelayed(this, 260)
            }
        }
        uiHandler.post(waveTick)

        // 描边层放在最后 → 画在最上层；不接收触摸，只用来暴露窗口真实边界
        borderView = WindowBorderView(this).also { bv ->
            root.addView(
                bv,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            bv.visibility = if (curBorder) View.VISIBLE else View.GONE
            bv.label = "${w}x$h"
        }
        AppLog.log("SVC", "buildWindow ${w}x$h 纯代码创建视图")
        videoView.setListener(object : PetVideoView.Listener {
            override fun onVideoEnded(name: String) {
                consecutiveErrors = 0
                engine.onAnimEnded(name)
                videoView.setMirror(engine.shouldMirror(engine.anim ?: ""))
            }

            override fun onVideoError(name: String, msg: String) {
                // 出错时推进动画链，避免停摆；连续出错则停止并提示（防死循环）
                AppLog.log("VIDEO", "播放失败 [$name]: $msg")
                consecutiveErrors++
                if (consecutiveErrors > 8) {
                    videoView.pausePlay()
                    showBubble("动画加载失败：$msg")
                } else {
                    engine.onAnimEnded(name)
                }
            }

            /**
             * 渲染彻底失败（窗口在、画面全透明）。
             *
             * 以前这种情况是**完全静默**的：着色器编译失败只留在 GL 线程里一行 `Log.e`，
             * 用户只看到"桌宠图案没了"，排查必须连电脑抓 logcat。
             * 现在三条路一起走：用户日志（App 内可看）+ 气泡提示 + 固定格式的 `RENDER broken` 行。
             */
            override fun onRenderBroken(stage: String, reason: String) {
                AppLog.log("RENDER", "service 收到 broken stage=$stage reason=$reason")
                AppLog.u("桌宠", "画面加载失败（$stage/$reason），设备信息已记录")
                // 先上"静态兜底"：哪怕 GL 完全画不出来，也要让用户看得见桌宠（定格一帧）
                installStaticFallback("$stage/$reason")
                showBubble("画面加载失败：$stage/$reason\n（关于 → 查看日志 有设备信息）", 9000)
            }
        })
        container = root
        wm.addView(root, lp)
        // 系统可能给 overlay 窗口留下 0.8 的合成 alpha → 启动后强推几次 1.0（见函数注释）
        forceWindowAlphaOpaque()
        uiHandler.postDelayed({ forceWindowAlphaOpaque() }, 600)
        uiHandler.postDelayed({ forceWindowAlphaOpaque() }, 2000)
        // 视觉窗口加完再加触摸窗口：后加的在上层，手势才能落到它身上
        if (windowPlan.splitTouchWindow) createTouchWindow()
    }

    // ================================================================ 触摸窗口（Android 12+）
    /**
     * 创建"触摸窗口"：一个透明的、只有人物包围盒大小的小窗口，专门收手势。
     *
     * 为什么需要它：Android 12+ 拿不到 `touchableRegion`（hidden API 被 deny），
     * 视觉窗口又必须做得比人物大（并集，才能换动画不 resize），于是透明区会把整片屏幕的点击吃掉。
     * 公开 API 里没有"窗口内自定义可触摸区域"，所以拆成两个窗口：
     *  - 视觉窗口：`FLAG_NOT_TOUCHABLE`，只负责画面；
     *  - 触摸窗口：跟着人物走，只有它能点 —— 等效于一加的 touchableRegion。
     */
    private fun createTouchWindow() {
        if (touchView != null) return
        val lp = params ?: return
        // 精确按"当前动画的非透明像素包围盒"来定触摸窗口：不再额外加余量。
        // 包围盒来自 frames.json（构建时离线统计的每段动画非透明像素并集），
        // 换动画时由 applyGeometry → syncTouchWindow(relayout=true) 自动更新。
        val pad = 0
        val w = (lp.width - insetL - insetR + pad * 2).coerceAtLeast(32)
        val h = (lp.height - insetT - insetB + pad * 2).coerceAtLeast(32)
        val tv = android.view.View(this)
        val tp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "dsh-pet-touch-$instanceId"
            x = lp.x + insetL - pad
            y = lp.y + insetT - pad
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }
        runCatching { wm.addView(tv, tp) }
            .onFailure { AppLog.log("WIN", "触摸窗口创建失败：${it.javaClass.simpleName} ${it.message}") }
        touchView = tv
        touchParams = tp
        AppLog.log("WIN", "触摸窗口 ${w}x$h @ ${tp.x},${tp.y}（视觉窗口 ${lp.width}x${lp.height} @ ${lp.x},${lp.y}）")
    }

    /** 让触摸窗口贴着人物包围盒（每帧移动、每次改几何都要同步） */
    private fun syncTouchWindow(relayout: Boolean = false) {
        val tv = touchView ?: return
        val tp = touchParams ?: return
        val lp = params ?: return
        // 精确按"当前动画的非透明像素包围盒"来定触摸窗口：不再额外加余量。
        // 包围盒来自 frames.json（构建时离线统计的每段动画非透明像素并集），
        // 换动画时由 applyGeometry → syncTouchWindow(relayout=true) 自动更新。
        val pad = 0
        val w = (lp.width - insetL - insetR + pad * 2).coerceAtLeast(32)
        val h = (lp.height - insetT - insetB + pad * 2).coerceAtLeast(32)
        val x = lp.x + insetL - pad
        val y = lp.y + insetT - pad
        if (relayout || tp.width != w || tp.height != h || tp.x != x || tp.y != y) {
            tp.width = w
            tp.height = h
            tp.x = x
            tp.y = y
            runCatching { wm.updateViewLayout(tv, tp) }
        }
    }

    /**
     * 强制把视觉窗口的合成 alpha 拉回 1.0。
     *
     * 背景：Xiaomi 13（Android 16）上实测 `dumpsys window` 里 `mAttrs alpha=0.8` /
     * `mShownAlpha=0.8`，导致"不透明度调到 100% 仍然有点透"。0.8 不是我们设的
     *（LayoutParams 默认 1.0），所以每次启动 + 稍后各强推一次覆盖系统留下的值。
     */
    private fun forceWindowAlphaOpaque() {
        val lp = params ?: return
        val c = container ?: return
        if (lp.alpha == 1f) {
            // 参数已经是 1.0，但系统侧可能仍是 0.8 → 用一次微小抖动逼 WMS 重新下发
            lp.alpha = 0.999f
            runCatching { wm.updateViewLayout(c, lp) }
        }
        lp.alpha = 1f
        runCatching { wm.updateViewLayout(c, lp) }
        AppLog.log("WIN", "forceAlpha lp.alpha=${lp.alpha}")
    }

    // ================================================================ 静态兜底（渲染坏掉时）
    /** 渲染坏掉时的定格视图（纯 View，不经过 GL） */
    private var staticFallback: android.view.View? = null
    /** 静态兜底取帧重试次数：渲染失败常常发生在 engine/catalog 还没建好的窗口初始化阶段 */
    private var fallbackAttempts = 0

    /**
     * 静态兜底：GL 画不出来时，取当前动画第 0 帧，把「左半 RGB + 右半 alpha 遮罩」合成成一张
     * 带透明通道的位图，贴到 ImageView 上。
     *
     * 为什么值得做：GLSurfaceView 一旦初始化失败或着色器编不过，窗口里就是一片**全透明**，
     * 用户看到的是"桌宠没了"。定格一帧至少还能用——点击、拖动、菜单全在 View 层，和渲染无关。
     */
    private fun installStaticFallback(reason: String) {
        if (staticFallback != null) return
        val c = container as? android.widget.FrameLayout ?: return
        val v = videoView
        scope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { buildStaticFrame() }.getOrNull()
            }
            if (bmp == null) {
                // 渲染失败常常发生在 initPet 之前（engine/catalog 还没建好）→ 稍后重试
                if (fallbackAttempts < 8) {
                    fallbackAttempts++
                    AppLog.log("RENDER", "静态兜底取帧为空，800ms 后重试（第 $fallbackAttempts 次）reason=$reason")
                    uiHandler.postDelayed({ installStaticFallback(reason) }, 800)
                } else {
                    AppLog.log("RENDER", "静态兜底放弃（多次取帧为空）reason=$reason")
                }
                return@launch
            }
            val iv = android.widget.ImageView(this@PetOverlayService).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
                setImageBitmap(bmp)
                translationX = v.translationX
                translationY = v.translationY
                pivotX = v.pivotX
                pivotY = v.pivotY
                scaleX = v.scaleX
                scaleY = v.scaleY
            }
            val wpx = (v.layoutParams?.width ?: 0).takeIf { it > 0 }
                ?: android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            val hpx = (v.layoutParams?.height ?: 0).takeIf { it > 0 }
                ?: android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            c.addView(iv, android.widget.FrameLayout.LayoutParams(wpx, hpx))
            staticFallback = iv
            AppLog.log("RENDER", "静态兜底已启用 reason=$reason bitmap=${bmp.width}x${bmp.height}")
        }
    }

    /** 取当前动画第 0 帧并合成透明通道（左半 RGB + 右半灰度遮罩），失败返回 null */
    private fun buildStaticFrame(): android.graphics.Bitmap? {
        // 这个函数可能在 initPet 之前被调用（窗口初始化阶段就挂了）→ 两个 lateinit 都要判
        val name = (if (this::engine.isInitialized) engine.anim else null)
            ?: (if (this::catalog.isInitialized) catalog.idle else null)
            ?: return null
        val rel = (if (this::catalog.isInitialized) catalog.files[name] else null) ?: return null
        val afd = assets.openFd("pet/videos/$rel")
        var mmr: android.media.MediaMetadataRetriever? = null
        val src = try {
            mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            mmr.getFrameAtTime(0)
        } catch (e: Throwable) {
            AppLog.log("RENDER", "取帧失败 $rel: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { afd.close() }
            runCatching { mmr?.release() }
        } ?: return null

        val w = src.width / 2
        val h = src.height
        if (w <= 0 || h <= 0) return null
        val rgb = IntArray(w * h)
        val mask = IntArray(w * h)
        src.getPixels(rgb, 0, w, 0, 0, w, h)
        src.getPixels(mask, 0, w, w, 0, w, h)
        src.recycle()
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val dst = IntArray(w * h)
        for (i in dst.indices) {
            val a = (mask[i] shr 16) and 0xFF          // 右半是灰度图，红通道即 alpha
            dst[i] = (a shl 24) or (rgb[i] and 0x00FFFFFF)
        }
        out.setPixels(dst, 0, w, 0, 0, w, h)
        return out
    }

    // ================================================================ 动画播放
    /**
     * 播放某段动画。
     *
     * **拖拽期间绝不动窗口几何**（这条是真机 A/B 试出来的，别再改回去）：
     * 拖拽起手会切到「被鼠标拖拽悬空反馈」，那段动画的包围盒比待机高（满屏 576 vs 515）。
     * 如果照常 `applyGeometry`，主线程虽然只要 1~5ms，但 `wm.updateViewLayout` 改窗口宽高会触发
     * SurfaceView 缓冲重分配 + 按脚底重排 x/y，正好落在手指刚动的那一帧上 →
     * 体感就是"拖动一开始卡一下、顿一帧"。跳过它起手立刻跟手，松手后再补几何（见 [flushPendingGeometry]）。
     *
     * 另外 `videoView.play()` 主线程只有 1~2ms，但**新解码器首帧要 35~90ms**（`Dsh_VIDEO 首帧已渲染`），
     * 这段画面定格的空窗由起手时的 [squashGrab] 挤压盖住。
     */
    private fun playAnimation(name: String) {
        try {
            val into = catalog.files[name] ?: run {
                AppLog.log("PLAY", "未知动画名：$name")     // 名字对不上 catalog 时留痕
                return
            }
            val t0 = SystemClock.uptimeMillis()
            var geoCost = 0L
            // 按这段动画自己的包围盒调整窗口（宽动画不再被裁、窄动画不再有大片死区），
            // 脚底锚点保持不变，所以看起来是"人物原地长大/收缩"
            //
            // **拖拽期间 / 抛掷飞行中都不动几何**：
            //  - 拖拽：改窗口宽高会触发 SurfaceView 缓冲重分配 + 按脚底重排 x/y，正好落在手指刚动
            //    的那一帧上 → "拖动一开始卡一下"（真机 A/B 试出来的，别改回去）。
            //  - 抛掷：飞行中换动画（撞墙/动画播完）会 applyGeometry → 可移动范围随之变化 →
            //    窗口位置被按新范围重新夹紧/按锚点重排 → **玩家看到"闪现到轨迹很远的地方"**。
            //    所以飞行中只换视频，几何等落地再补（见 [flushPendingGeometry]）。
            if (dragging || physMode != null) {
                pendingGeometryAfterDrag = true
                if (physMode != null) AppLog.log("SVC", "抛掷飞行中延后几何：$name")
            } else {
                applyGeometry(geometryFor(name), keepAnchor = true)
                geoCost = SystemClock.uptimeMillis() - t0
            }
            val tVideo = SystemClock.uptimeMillis()
            videoView.play(name, into, playSpeedOverride ?: curSpeed.toFloat())
            videoView.setMirror(engine.shouldMirror(name))
            // 隐藏时不播（省电）：显示时恢复
            if (!visible) videoView.pausePlay()
            val cost = SystemClock.uptimeMillis() - t0
            // 只在 logcat 留一行（不进文件日志）：换动画是高频事件，慢了才值得记一笔
            if (cost >= 30) {
                android.util.Log.i(
                    "Dsh_SVC",
                    "play $name 耗时 ${cost}ms（geometry=$geoCost video=${SystemClock.uptimeMillis() - tVideo}ms dragging=$dragging）",
                )
            }
        } catch (e: Throwable) {
            AppLog.log("PLAY", "playAnimation($name) 异常: ${e.message}")
        }
    }

    /** 松手后把拖拽期间欠下的窗口几何补上（见 [playAnimation] 的注释） */
    private fun flushPendingGeometry() {
        if (!pendingGeometryAfterDrag) return
        pendingGeometryAfterDrag = false
        runCatching { applyGeometry(geometryFor(engine.anim), keepAnchor = true) }
            .onFailure { AppLog.log("SVC", "补几何失败：${it.message}") }
    }

    // ================================================================ 手势
    // ---- 点击手势：1击=退出语音（若运行中），2击=文本对话，3击=语音对话 ----
    private var waveView: PetVoiceWaveView? = null

    /** 窗口描边层（调试开关打开时才可见） */
    private var borderView: WindowBorderView? = null

    // ---- 桌宠内置语音（不进任何页面）----
    private var voiceLoop: com.dshpet.android.voice.core.VoiceLoop? = null

    private fun startVoiceInOverlay() {
        if (voiceLoop != null) return
        // 没有麦克风权限时别直接失败：Service 里弹不出权限框，把语音页捞起来让它申请，
        // 并提示用户选「始终允许」——否则一切到别的应用，一次性授权会立刻失效（收音断续）。
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            uiHandler.post {
                bubble?.showText("先给我麦克风权限吧，记得选「始终允许」", durationMs = 2600)
            }
            openChatUi(voice = true)
            return
        }
        val l = com.dshpet.android.voice.core.VoiceRuntime.ensureStarted(this)
        if (l == null) {
            AppLog.u("语音", "启动失败：请先在「AI 对话」填好 Key")
            uiHandler.post { bubble?.showText("先去设置里填 Key 哦", durationMs = 2500) }
            return
        }
        voiceLoop = l
        AppLog.u("语音", "桌宠内置语音已启动")
    }

    private fun stopVoiceInOverlay() {
        com.dshpet.android.voice.core.VoiceRuntime.stop()
        voiceLoop = null
        AppLog.u("语音", "桌宠内置语音已停止")
    }



    // 防重复触发：同一次手势只允许一次动作


    /** 进程级去重：多实例/多回调叠加时也只触发一次 */





    private fun openChatUi(voice: Boolean) {
        runCatching {
            val i = android.content.Intent(this, com.dshpet.android.voice.VoiceLoopActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (voice) i.putExtra("autoVoice", true) else i.putExtra("textOnly", true)
            startActivity(i)
            AppLog.u("桌宠", if (voice) "三击：打开语音对话" else "双击：打开文本对话")
        }.onFailure { android.util.Log.e("DshTap", "open chat failed", it) }
    }


    private lateinit var taps: TapSequencer
    private var gLastFeedbackAt = 0L

    /** 抬起瞬间的廉价反馈：动画 / 台词 / 音效 */
    private fun onTapFeedback(count: Int) {
        // 点击的动画 / 台词 / 音效由触摸分支原有的 onTap() 负责，这里不再重复触发，
        // 否则一次点击会响两次（这也是"每次点击都两次音效"的直接原因）。
    }

    /** 结算时派发语义动作 */
    private fun onTapCommitted(count: Int) {
        AppLog.u("桌宠", "手势 " + count + " 击")
        when (count) {
            2 -> runCatching { toggleQuickChat() }
            3 -> startVoiceInOverlay()
            else -> if (voiceLoop != null) stopVoiceInOverlay()
        }
    }

    /**
     * 手势总入口（窗口级 OnTouchListener），三个状态各管一段：
     *  - `ACTION_DOWN`：记锚点、起点击计数、准备拖拽；
     *  - `ACTION_MOVE`：超过阈值判拖拽 → 暂停动画链、跟手移动；开了物理就交给弹簧；
     *  - `ACTION_UP`：判定"点击 / 拖拽 / 抛掷"，抛掷进入物理 tick，点击交给 [onTap]。
     * 锁定位置、仅长按可拖动、SHIFT 语义都在这里分支。
     */
    private fun installGestures() {
        // 有触摸窗口时手势挂在小窗口上（视觉窗口是 NOT_TOUCHABLE，收不到事件）
        val v = touchView ?: container ?: return
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                taps.onDown(ev.rawX, ev.rawY)
                    // 上一次抛掷被打断（还没落停就又按下）：把欠下的 onDragEnd 补上，
                    // 否则引擎一直认为"还在拖拽"，悬空动画会永远循环下去（真机上看到的就是
                    // 桌宠一直保持被拎着的姿势、每 ~7s 闪一下）。
                    if (deferDragEnd) {
                        deferDragEnd = false
                        engine.onDragEnd()
                        flushPendingGeometry()
                    }
                    pressActive = true
                    dragging = false
                    longPressFired = false
                    justDragged = false
                    downX = ev.x; downY = ev.y
                    downRawX = ev.rawX; downRawY = ev.rawY
                    grabOffsetX = downRawX - engine.winX
                    grabOffsetY = downRawY - engine.winY
                    // 用 uptimeMillis（单调时钟）：拖拽轨迹/松手初速都按它算，
                    // 和 ACTION_MOVE、ACTION_UP 里取的 now 必须是同一时钟，否则 dt 会算成天文数字
                    lastMoveMs = SystemClock.uptimeMillis()
                    trail.clear()
                    trail.add(Triple(lastMoveMs, ev.rawX, ev.rawY))
                    physPos = doubleArrayOf(engine.winX.toDouble(), engine.winY.toDouble())
                    physVel = doubleArrayOf(0.0, 0.0)
                    physMode = null
                    engine.cancelMove()
                    if (curLock) {
                        // 锁定位置：只是禁止拖动；**长按菜单仍要能弹**（以前在这里直接 return，
                        // 长按 runnable 根本没 post，锁了就再也打不开菜单）
                        uiHandler.removeCallbacks(longPressRunnable)
                        uiHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                        return@setOnTouchListener true
                    }
                    // 长按菜单 / 长按拖动：默认 400ms（"仅长按可拖动"模式下更短，240ms）
                    uiHandler.removeCallbacks(longPressRunnable)
                    uiHandler.postDelayed(
                        longPressRunnable,
                        if (curShiftDrag) LONG_PRESS_SHIFT_MS else LONG_PRESS_MS,
                    )
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                runCatching { taps.onPointerDown() }
                    // 拖拽中第二指按下 → 进入「弹弓蓄力」。
                    // 条件里带上 pressActive：很多人的手势是"两指一起按下去再拉"，
                    // 这时还没过拖动阈值（dragging=false），只认 dragging 的话弹弓就完全不响应
                    // （用户反馈"小米 13 上弹弓消失了"的直接原因之一）。
                    // 锚点用**桌宠当前中心**：ACTION_POINTER_DOWN 的 ev.rawX/rawY 是第一根手指的坐标，
                    // 拿它当锚点的话拉距永远约等于 0，弹弓等于没实现。
                    if ((dragging || pressActive) && ev.pointerCount >= 2 && !slingshotAiming) {
                        slingshotAiming = true
                        slingshotPointerId = ev.getPointerId(ev.actionIndex)
                        slingshotAnchorX = engine.winX + engine.winW / 2f
                        slingshotAnchorY = engine.winY + engine.winH / 2f
                        slingshotPullX = 0f
                        slingshotPullY = 0f
                        AppLog.u("桌宠", "弹弓蓄力：锚点 ${slingshotAnchorX.roundToInt()},${slingshotAnchorY.roundToInt()}")
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // 松开的正是蓄力那根手指 → 发射（第一根手指还按着也算）
                    if (slingshotAiming && ev.getPointerId(ev.actionIndex) == slingshotPointerId) {
                        launchSlingshot()
                        dragging = false
                        pressActive = false
                        flushPendingGeometry()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                lastTouchEventTime = ev.eventTime
                runCatching { taps.onMove(ev.rawX, ev.rawY) }
                    if (!pressActive) return@setOnTouchListener true
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (slingshotAiming) {
                        // 蓄力中：只看**蓄力那根手指**相对锚点的位移，桌宠跟着被拉出去 35%（橡皮筋感）
                        val idx = ev.findPointerIndex(slingshotPointerId)
                        if (idx >= 0) {
                            val rx = ev.rawX + (ev.getX(idx) - ev.getX(0))
                            val ry = ev.rawY + (ev.getY(idx) - ev.getY(0))
                            slingshotPullX = rx - slingshotAnchorX
                            slingshotPullY = ry - slingshotAnchorY
                            moveWindow(
                                (slingshotAnchorX + slingshotPullX * SLINGSHOT_FOLLOW -
                                        engine.winW / 2f).toInt(),
                                (slingshotAnchorY + slingshotPullY * SLINGSHOT_FOLLOW -
                                        engine.winH / 2f).toInt(),
                            )
                        }
                        return@setOnTouchListener true
                    }
                    val threshold = (PetEngine.DRAG_THRESHOLD * curScale * density).coerceAtLeast(12.0)
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > threshold) {
                        collisionMember?.infiniteMass = true
                        if (curShiftDrag && !longPressFired) {
                            // 仅长按可拖动：未长按的拖动被忽略，且取消按压状态
                            pressActive = false
                            uiHandler.removeCallbacks(longPressRunnable)
                            return@setOnTouchListener true
                        }
                        uiHandler.removeCallbacks(longPressRunnable)
                        if (curLock) return@setOnTouchListener true
                        dragging = true
                        runCatching { taps.cancelAll() }
                        squashGrab()    // 先把"被抓住"的挤压反馈放出去，盖住解码器起播的 ~90ms
                        engine.onDragStart()
                    }
                    if (dragging) {
                        // **1:1 跟手**：拖拽期间直接把窗口跟到手指位置（内部按帧合并提交，见 moveWindow）。
                        // 旧实现开了「拖动物理」时走 physMode="drag" 的弹簧跟随，而物理 tick 是 30ms 一次，
                        // 于是手指移动 → 30fps 的弹簧 → 又慢又抖，这就是"拖动有卡顿感"的主因。
                        // 现在弹簧只负责**松手之后**的抛掷惯性（见 ACTION_UP）。
                        moveWindow(
                            (ev.rawX - grabOffsetX).toInt(),
                            (ev.rawY - grabOffsetY).toInt(),
                        )
                        if (curPhysics) {
                            val now = SystemClock.uptimeMillis()
                            trail.add(Triple(now, ev.rawX, ev.rawY))
                            // 只留最近 32 个采样（120Hz ≈ 270ms），够 estimateReleaseVelocity 用；
                            // 直接原地裁剪，避免以前每个 MOVE 事件都 filter 出一个新 List（GC 抖动 → 掉帧）
                            if (trail.size > TRAIL_MAX) trail.subList(0, trail.size - TRAIL_MAX).clear()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    if (!dragging && !longPressFired) taps.onUp(ev.rawX, ev.rawY, ev.pointerCount)
                }
                    uiHandler.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        justDragged = true
                        uiHandler.postDelayed({ justDragged = false }, 150)
                        collisionMember?.infiniteMass = false
                        // 弹弓发射：按蓄力拉伸发射（方向 = 拉的反方向，力度 ∝ 拉距）
                        if (slingshotAiming) {
                            // **不立刻 engine.onDragEnd()**：切动画要换解码器（首帧 35~90ms）+
                            // 改窗口尺寸（resize），落在发射那一瞬间就是用户看到的"闪现 + 卡一下"。
                            // 拖着"悬空"动画飞，停稳后再切回待机（见 stepPhysics 的静止分支）。
                            if (launchSlingshot()) deferDragEnd = true else engine.onDragEnd()
                            dragging = false
                            pressActive = false
                            if (!deferDragEnd) flushPendingGeometry()
                            return@setOnTouchListener true
                        }
                        if (curPhysics) {
                            val now = SystemClock.uptimeMillis()
                            val (vx, vy) = PetEngine.estimateReleaseVelocity(
                                trail, now, cap = PetEngine.throwSpeedCap(curThrowStrength)
                            )
                            AppLog.log(
                                "THROW",
                                "松手初速 v=${hypot(vx, vy).toInt()}（档位 $curThrowStrength，上限 ${PetEngine.throwSpeedCap(curThrowStrength).toInt()}）",
                            )
                            if (hypot(vx, vy) < PetEngine.DEAD_ZONE_SPEED) {
                                physMode = null
                                engine.onDragEnd()
                                flushPendingGeometry()
                                savePosition()
                            } else {
                                physVel = doubleArrayOf(vx, vy)
                                physPos = doubleArrayOf(engine.winX.toDouble(), engine.winY.toDouble())
                                beginThrow()
                                deferDragEnd = true     // 同上：飞完再切动画
                            }
                        } else {
                            engine.onDragEnd()
                            flushPendingGeometry()
                            savePosition()
                        }
                    } else if (!longPressFired && !justDragged) {
                        onTap()
                    } else if (longPressFired && !dragging && !justDragged && menuWindow == null) {
                        // 长按后**原地松手**：也弹菜单。
                        // 之前只有"非长按拖动模式"才会在 onLongPress 里直接弹；
                        // 开了「仅长按可拖动」时，长按只是武装拖动，松手什么都不发生 → 表现为"有时长按没反应"。
                        openMenu()
                    }
                    dragging = false
                    pressActive = false
                    flushPendingGeometry()      // 拖拽期间欠下的窗口尺寸，松手后补上
                    true
                }
                else -> true
            }
        }
    }

    /** 长按：弹出 MD3 菜单（[PetMenu]），菜单期间桌宠的点击响应暂时让位。 */
    private fun onLongPress() {
        if (!pressActive) return
        longPressFired = true
        if (curShiftDrag) {
            // 仅长按可拖动：长按后进入"待拖动"状态，松手未移动则弹菜单
            return
        }
        openMenu()
    }

    /** 单击：Q 弹一下 + 播一段随机"回应"动画 + 音效；按配置决定是否顺带报余额/自言自语。 */
    private fun onTap() {
        // 双击 → 快速对话气泡（上游 PR#40）
        val now = System.currentTimeMillis()
        if (false) /* 双击交给 TapSequencer */ {
            lastTapMs = 0
            toggleQuickChat()
            return
        }
        lastTapMs = now
        engine.onTap()
        squash()
        // 点击台词绑定（上游 v4.1.0：自定义台词+动画）
        val talk = curClickTalk
        if (talk.isNotBlank()) {
            showBubble(talk, 4000)
        }
        if (curClickSound) playClickSound()
        if (curClickBalance) {
            showBalanceInBubble()
        } else if (curClickSelfTalk) {
            // 点击自言自语独立于周期性自言自语总开关
            showRandomSelfTalk()
        }
    }

    // ================================================================ Q 弹
    private var squashAnim: android.animation.ValueAnimator? = null

    /**
     * 抓住瞬间的挤压反馈。
     *
     * 拖动起手时引擎会切到「被拖拽悬空」那段视频，而新解码器**首帧约 90ms**（真机实测：
     * `play … 2ms` 之后 90ms 才 `首帧已渲染`）。这 90ms 里画面定在上一帧、窗口却已经跟着手指走了，
     * 看起来就是"拖动一开始卡一下"。
     *
     * 所以起手时立刻给一个 150ms 的缩放（1 → 0.94 → 1.03 → 1）：**视觉上一直在动**，
     * 解码器起播那一下就被盖住了，手感还多了个"被抓住"的反馈。
     */
    private fun squashGrab() {
        squashAnim?.cancel()
        videoView.pivotX = videoView.width / 2f
        videoView.pivotY = videoView.height.toFloat()
        val anim = android.animation.ValueAnimator.ofFloat(1f, 0.94f, 1.03f, 1f).apply {
            duration = 150
            addUpdateListener {
                val v = it.animatedValue as Float
                videoView.scaleX = v
                videoView.scaleY = v
            }
        }
        squashAnim = anim
        anim.start()
    }

    private fun squash() {
        squashAnim?.cancel()
        videoView.pivotX = videoView.width / 2f
        videoView.pivotY = videoView.height.toFloat()
        val anim = android.animation.ValueAnimator.ofFloat(1f, 0.78f, 1.06f, 1f).apply {
            duration = 240
            addUpdateListener {
                videoView.scaleY = it.animatedValue as Float
            }
        }
        squashAnim = anim
        anim.start()
    }

    // ================================================================ 移动窗口
    /**
     * 移动窗口并**同步**写回 `engine.winX/winY`（不同步的话动画链的落点会算错）。
     *
     * 性能：**拖动时每个 vsync 只提交一次 `updateViewLayout`**。
     * 触摸 MOVE 在 120Hz 屏上每秒来 100+ 个事件，过去每个事件都直接 `updateViewLayout`，
     * WindowManager/SurfaceFlinger 一帧里要重排好几次窗口，表现出来就是"拖动粘手、一跳一跳"。
     * 现在拖拽期间只记目标、用 Choreographer 在下一帧统一提交；
     * 逻辑位置（`engine.winX/winY`、碰撞成员）仍然是**立刻**更新的，动画链/碰撞不受帧率影响。
     * 非拖拽（散步/抛掷/落点）本来就 ≤30 次/秒，直接提交，省掉一帧延迟。
     */
    private fun moveWindow(xPx: Int, yPx: Int) {
        val cx = xPx.coerceIn(windowXRange())
        val cy = yPx.coerceIn(windowYRange())
        // 注意：**不要**在这里再夹一道"窗口不出屏幕"然后靠平移视频层补偿。
        // 试过：窗口位置（updateViewLayout）和子 View 平移是两条独立的合成事务，
        // 高速飞行时两者会差一帧 → 桌宠"闪现到轨迹很远的地方"（用户实测）。
        // 位置只有一个来源：窗口坐标。
        engine.syncPosition(cx, cy)
        collisionMember?.let {
            it.x = cx.toDouble(); it.y = cy.toDouble()
            it.w = engine.winW; it.h = engine.winH
        }
        val wjx = cx - pendingWinX
        val wjy = cy - pendingWinY
        // 非物理、非拖拽时窗口位置突变 = 被几何重排或夹紧改写（"闪现到很远"的另一来源）
        if (!dragging && physMode == null && (kotlin.math.abs(wjx) > 150 || kotlin.math.abs(wjy) > 150)) {
            AppLog.log("WIN", "窗口跳变 Δ=${wjx},${wjy} → $cx,$cy anim=${engine.anim}")
        }
        pendingWinX = cx
        pendingWinY = cy
        if (!dragging) {
            // 拖拽之外（启动落点 / 散步 / 抛掷 / 回到右下角）直接提交：
            // 这些场景每秒最多 30 次，没必要等帧，也避免启动时"先在 (0,0) 闪一下"
            applyWindowPosition()
        } else if (!moveFrameScheduled) {
            moveFrameScheduled = true
            choreographer.postFrameCallback(applyPendingMove)
        }
    }

    /**
     * 抛掷物理的**单帧**推进：重力积分 / 边界反弹 / 摩擦，静止后落库坐标。
     *
     * @param dt 真实帧间隔（秒）。以前这里写死 `dt = 0.016` 却由 33ms 的 ticker 驱动，
     *   既慢又一顿一顿的；而且带碰撞成员时会**连积两次**（相当于 2×速度）——两个毛病一起修了。
     */
    private fun stepPhysics(dt: Double) {
        if (physMode != "throw") return          // 拖拽期间不再有弹簧态（跟手是 1:1 的），只剩抛掷
        // 手指还按着的时候**绝不让物理动窗口**：拖拽是 1:1 跟手的，
        // 物理再插一脚就会和手指抢窗口位置 → 视觉上"闪跳"（开重力感应后尤其明显）。
        if (dragging || pressActive) return
        throwFrames++
        // **子步长**：某一帧被系统拖到 30~40ms 时（切换应用、开别的软件），一次大步积分会让桌宠"瞬移"。
        // 拆成 ≤8ms 的小步跑，轨迹和反弹角度都不会变形，视觉上也更容易接受掉帧。
        var remain = dt
        while (remain > 1e-6 && physMode == "throw") {
            val h = min(remain, MAX_PHYS_SUBSTEP)
            stepPhysicsOnce(h)
            remain -= h
        }
    }

    private fun stepPhysicsOnce(dt: Double) {
        val xr = windowXRange(); val yr = windowYRange()
        val left = xr.first
        val top = yr.first
        val right = xr.last
        val bottom = yr.last
        if (right <= left || bottom <= top) { physMode = null; return }
        // 碰撞中枢（其它小肥鱼）在我这一帧之间改过速度 → 采纳（被撞飞）。
        // **必须用 pushedVx/pushedVy 比较**：中枢里存的是"我上一次推给它的值"，
        // 直接拿 `m.vx != physVel[0]` 比，起抛第一帧就会把刚设好的初速冲成 0（桌宠直接垂直掉下去，
        // 完全没有横向飞行和撞墙反弹——2026-10-26 修的就是这个）。
        val m = collisionMember
        if (m != null) {
            if (m.vx != pushedVx || m.vy != pushedVy) physVel = doubleArrayOf(m.vx, m.vy)
            m.vx = physVel[0]; m.vy = physVel[1]
            pushedVx = physVel[0]; pushedVy = physVel[1]
        }
        val g = gravityForStep()
        // 手机平放（平面重力≈0）时没有"下"，加一点阻尼让它慢慢停住——
        // 否则零重力下桌宠会一直匀速漂下去（throwStep 只有落地摩擦，没有空气阻力）。
        // 只在**开了重力感应**时加：没开的话重力是固定向下的，阻尼会让抛掷显得"发飘"。
        if (curGravitySensor && gravityValid &&
            hypot(g.first, g.second) < PetEngine.GRAVITY * FLAT_GRAVITY_FRAC
        ) {
            val damp = (1.0 - 6.0 * dt).coerceIn(0.5, 1.0)
            physVel = doubleArrayOf(physVel[0] * damp, physVel[1] * damp)
        }
        val r = PetEngine.throwStep(
            physPos[0], physPos[1], physVel[0], physVel[1], dt,
            left, top, right, bottom, g.first, g.second,
        )
        // 单帧位移异常检测：正常情况下速度再快也是"每帧几十像素"。
        // 出现上百像素的跳变只可能是坐标被别的路径改写（几何重算 / 夹紧 / 引擎纠正），
        // 用户看到的就是"闪现到轨迹很远的地方"。
        val jdx = r.px - physPos[0]
        val jdy = r.py - physPos[1]
        if (kotlin.math.abs(jdx) > 120 || kotlin.math.abs(jdy) > 120) {
            AppLog.log(
                "PHYS",
                "单帧跳变 Δ=${jdx.roundToInt()},${jdy.roundToInt()} dt=${"%.4f".format(dt)} " +
                    "v=${physVel[0].roundToInt()},${physVel[1].roundToInt()} win=${engine.winX},${engine.winY}",
            )
        }
        physPos = doubleArrayOf(r.px, r.py)
        physVel = doubleArrayOf(r.vx, r.vy)
        if (m != null) {
            m.vx = physVel[0]; m.vy = physVel[1]
            pushedVx = physVel[0]; pushedVy = physVel[1]
        }
        if (r.bounced) {
            throwBounces++
            // **撞墙掉能量**：不加这一条，120Hz 屏幕上高能抛掷会贴着墙"哒哒哒"弹个不停
            //（用户看到的就是"撞到边缘后一直闪"）。反弹次数越多衰减越狠，保证几秒内落停。
            val lose = when {
                throwBounces > 12 -> 0.55
                throwBounces > 6 -> 0.72
                else -> 0.86
            }
            physVel = doubleArrayOf(physVel[0] * lose, physVel[1] * lose)
            if (m != null) { m.vx = physVel[0]; m.vy = physVel[1]; pushedVx = physVel[0]; pushedVy = physVel[1] }
        }
        // 逐帧轨迹（只在抛掷时打，用于定位"闪到很远"到底是哪一帧位置被改写）
        if (throwTrace) {
            android.util.Log.i("Dsh_TRACE", "t=$throwFrames x=${physPos[0].roundToInt()} y=${physPos[1].roundToInt()} vx=${physVel[0].roundToInt()} vy=${physVel[1].roundToInt()} b=${throwBounces} win=${engine.winX},${engine.winY} ${engine.winW}x${engine.winH}")
        }
        moveWindow(physPos[0].roundToInt(), physPos[1].roundToInt())
        val speed = hypot(r.vx, r.vy)
        if (isAtRestNow(r.py, r.vx, r.vy, bottom, r.bounced, speed)) {
            physMode = null
            lastRestMs = SystemClock.uptimeMillis()
            gravityWakeHits = 0
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) }
            val dur = SystemClock.uptimeMillis() - throwStartMs
            if (dur >= 300) {
                // 抛掷的有效帧率（排查"弹跳卡卡的"时看这条：以前是 30fps）
                android.util.Log.i(
                    "Dsh_SVC",
                    "抛掷结束：$throwFrames 帧 / ${dur}ms ≈ ${throwFrames * 1000 / dur}fps，撞墙 $throwBounces 次",
                )
            }
            // 拖着"悬空"动画飞完 → 现在才切回待机/补几何（避免发射那一瞬间解码器+resize 的闪卡）
            if (deferDragEnd) {
                deferDragEnd = false
                engine.onDragEnd()
            }
            // 落地后统一补几何：拖拽/抛掷飞行期间欠下的窗口尺寸与偏移，在这里一次算清
            flushPendingGeometry()
            // 底部落点自检：像素脚底相对"可用区下沿"的位置（应该正好贴着，不下沉）
            val pb = pixelBottom()
            if (pb > usableBottom() + 1) {
                android.util.Log.i(
                    "Dsh_SVC",
                    "底部落点：像素底=$pb 可用下沿=${usableBottom()} 超出=${pb - usableBottom()}px（人物像素高 ${visibleH()}px）",
                )
            }
            savePosition()
        }
    }

    /**
     * 停稳判定。
     *
     * 没开重力感应时沿用 [PetEngine.isAtRest]（"落在屏幕底边 + 速度够小"）。
     * 开了重力感应，重力可能朝左/右/上，所以改成"速度够小 + 贴住重力那一面"，
     * 否则手机横过来之后桌宠永远不认为自己停了（物理循环一直跑）。
     */
    /**
     * 停稳判定。
     *
     * 没开重力感应时沿用 [PetEngine.isAtRest]（"落在屏幕底边 + 速度够小"）。
     * 开了就完全按传感器来：
     *  - 平面重力几乎为 0（手机平放）→ 桌宠是"飘着"的，速度小就算停，不再硬往屏幕下沿靠；
     *  - 否则要求"贴住重力那一面"（左/右/上/下都可能）。
     */
    private fun isAtRestNow(py: Double, vx: Double, vy: Double, bottom: Int, bounced: Boolean, speed: Double): Boolean {
        // 没开重力感应：走引擎的"落在屏幕底边 + 速度够小"（此时重力只作用于**抛掷**，
        // 不会把静止的桌宠往下拽——静止唤醒那一路要求开了重力感应，见 moveTicker）。
        if (!curGravitySensor || !gravityValid) return PetEngine.isAtRest(py, vx, vy, bottom, bounced, speed)
        if (speed >= 60) return false
        val g = gravityForStep()
        // 平放：没有方向，飘着，速度小就算停
        if (hypot(g.first, g.second) < PetEngine.GRAVITY * FLAT_GRAVITY_FRAC) return true
        return isRestingOnGravitySide()
    }

    /** 当前窗口是否已经贴住"重力那一面"（开了重力感应才用） */
    private fun isRestingOnGravitySide(): Boolean {
        val g = gravityForStep()
        if (hypot(g.first, g.second) < PetEngine.GRAVITY * FLAT_GRAVITY_FRAC) return true  // 没有明确"下" → 视为停住
        val xr = windowXRange(); val yr = windowYRange()
        val tol = 16
        return if (abs(g.first) > abs(g.second)) {
            if (g.first > 0) engine.winX >= xr.last - tol else engine.winX <= xr.first + tol
        } else {
            if (g.second > 0) engine.winY >= yr.last - tol else engine.winY <= yr.first + tol
        }
    }

    /**
     * 这一帧用的重力（px/s²，y 向下为正）。
     *
     * - 开了「重力感应」→ **完全按传感器**：手机横过来重力就朝侧面，平放（平面分量≈0）就没有重力；
     * - **没开重力感应** → 用默认的"向下"，但**只作用于被甩出去的抛掷运动**（要能落回屏幕底边、能弹），
     *   静止的桌宠不会被它拽下去 —— 静止唤醒那条路要求 `curGravitySensor`（见 moveTicker）。
     *
     * 这版这么分工是因为：把默认重力整个去掉后，用户反馈"拖动物理几乎没有了"
     * （甩出去不落、不弹，一路飘走）—— 而 ta 想要的其实是"别自己往下滑"。
     */
    private fun gravityForStep(): Pair<Double, Double> {
        if (!curGravitySensor || !gravityValid) return 0.0 to PetEngine.GRAVITY
        val k = PetEngine.GRAVITY / 9.81
        // 设备坐标 Y 朝上、屏幕 Y 朝下，所以 y 要取正、x 取反（见 setGravitySensor 注释）
        return (accelX * k) to (accelY * k)
    }

    /**
     * 甩手机给桌宠的冲量。
     *
     * 方向 = 加速度相对重力的偏离方向（手往哪甩，它就往哪飞）；大小按冲击强度换算，
     * 再用「甩出力度」档位上限夹一刀。已经在飞的时候是**叠加**冲量，不会重置位置。
     */
    private fun shakeKick(ux: Double, uy: Double, impact: Double) {
        val cap = PetEngine.throwSpeedCap(curThrowStrength)
        if (physMode == "throw") {
            val add = (impact * SHAKE_SPEED_PER_G).coerceAtMost(cap)
            physVel = doubleArrayOf(
                (physVel[0] + ux * add).coerceIn(-cap, cap),
                (physVel[1] + uy * add).coerceIn(-cap, cap),
            )
            AppLog.log(
                "SVC",
                "甩动叠加：impact=${impact.roundToInt()} → v=${physVel[0].roundToInt()},${physVel[1].roundToInt()}",
            )
            return
        }
        val speed = (impact * SHAKE_SPEED_PER_G).coerceIn(SHAKE_MIN_SPEED, cap)
        // 方向 = 加速度相对慢速重力的偏离方向：手往右甩 → 桌宠被甩向右边（惯性同向）
        physVel = doubleArrayOf(ux * speed, uy * speed)
        physPos = doubleArrayOf(engine.winX.toDouble(), engine.winY.toDouble())
        beginThrow()
        AppLog.log("SVC", "甩动：impact=${impact.roundToInt()} → ${speed.roundToInt()}px/s")
    }

    /**
     * 开关「重力感应」：注册/注销加速度计。
     *
     * 坐标换算（Android 传感器坐标：X 右、**Y 朝上**、Z 朝屏幕外；加速度计静止时读数是"反重力"方向）：
     * - 手机竖着拿：读数 ≈ (0, +9.81, 0) → 屏幕坐标重力应朝下(+y)  → `accelY = +a.y`
     * - 手机右边朝下：读数 ≈ (-9.81, 0, 0) → 屏幕坐标重力朝右(+x)  → `accelX = -a.x`
     */
    private fun setGravitySensor(on: Boolean) {
        if (on == (accelListener != null)) return
        if (on) {
            val sm = runCatching {
                getSystemService(android.hardware.SensorManager::class.java)
            }.getOrNull()
            val s = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            if (sm == null || s == null) {
                AppLog.log("SVC", "重力感应：本机没有加速度计")
                return
            }
            val l = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    val ax = -e.values[0]
                    val ay = e.values[1]
                    val az = e.values[2]
                    // 低通（0.4）：抖动会被磨平，转动手机时约 0.12s 跟上
                    // （原来是 0.25 ≈ 0.2s，用户反馈"普通场景下太慢了"）
                    val k = 0.55
                    accelX = accelX * (1 - k) + ax * k
                    accelY = accelY * (1 - k) + ay * k
                    // 慢速重力估计（k=0.06 ≈ 0.3s）：只给"甩动方向"用。
                    // 用快的那份算方向的话，手机连续抖 200ms 后低通就跟上了，偏差≈0 → 方向算不出来。
                    gravSlowX = gravSlowX * 0.94 + ax * 0.06
                    gravSlowY = gravSlowY * 0.94 + ay * 0.06
                    gravityValid = true

                    // ---- 甩手机 → 给桌宠一个冲量（"用力甩，它在屏幕里乱撞"）----
                    // 力度用**合加速度相对 1g 的偏差**（手机随便动一下 |a| 都会变，所以门槛给到半个 g）；
                    // 方向用"当前加速度 − 慢速重力"的平面分量 —— 也就是手往哪甩，桌宠往哪飞。
                    val magAll = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
                    val impact = abs(magAll - 9.81)
                    val now = SystemClock.uptimeMillis()
                    if (curGravitySensor && impact > SHAKE_MIN_IMPACT && now - lastShakeMs > SHAKE_COOLDOWN_MS &&
                        !dragging && !pressActive
                    ) {
                        val dx = ax - gravSlowX
                        val dy = ay - gravSlowY
                        val len = hypot(dx, dy)
                        if (len > 0.8) {
                            lastShakeMs = now
                            shakeKick(dx / len, dy / len, impact)
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
            }
            // **注册到独立线程**：不传 handler 时加速度计回调走主线程（SENSOR_DELAY_GAME ≈50 次/秒），
            // 拖拽时这些回调会和 onTouch / 搬窗口抢主线程 —— 用户反馈"开重力感应 + 拖动物理时不跟手"。
            runCatching {
                sensorThread = sensorThread ?: android.os.HandlerThread("pet-gravity").apply { start() }
                sm.registerListener(
                    l, s, android.hardware.SensorManager.SENSOR_DELAY_GAME,
                    android.os.Handler(sensorThread!!.looper),
                )
            }.onFailure {
                // 个别 ROM 对带 handler 的重载有兼容问题：退回主线程注册（功能优先）
                runCatching { sm.registerListener(l, s, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
            }
            sensorManager = sm
            accelListener = l
            AppLog.log("SVC", "重力感应 开")
        } else {
            accelListener?.let { runCatching { sensorManager?.unregisterListener(it) } }
            sensorManager = null
            accelListener = null
            gravityValid = false
            accelX = 0.0; accelY = 0.0
            AppLog.log("SVC", "重力感应 关")
        }
    }

    /**
     * 弹弓发射：把当前的拉距换算成初速，方向与拉的方向**相反**（拉得越远飞得越快）。
     *
     * 力度换算：`速度 = 拉距(px) × 22`，再夹到「甩出力度」档位的上限。
     * 100px → 2200px/s，200px → 4400px/s（标准档上限 4800），甩几下能横穿整个屏幕。
     *
     * @return true = 真的发射了
     */
    private fun launchSlingshot(): Boolean {
        if (!slingshotAiming) return false
        slingshotAiming = false
        slingshotPointerId = -1
        val len = hypot(slingshotPullX.toDouble(), slingshotPullY.toDouble())
        val cap = PetEngine.throwSpeedCap(curThrowStrength)
        val speed = (len * SLINGSHOT_PX_TO_SPEED).coerceAtMost(cap)
        if (len < SLINGSHOT_MIN_PULL || speed < SLINGSHOT_MIN_SPEED) {
            AppLog.u("桌宠", "弹弓取消：只拉了 ${len.roundToInt()}px（要 ≥${SLINGSHOT_MIN_PULL.roundToInt()}px）")
            return false
        }
        physVel = doubleArrayOf(
            -slingshotPullX / len * speed,
            -slingshotPullY / len * speed,
        )
        physPos = doubleArrayOf(engine.winX.toDouble(), engine.winY.toDouble())
        beginThrow()
        AppLog.u("桌宠", "弹弓发射：拉 ${len.roundToInt()}px → ${speed.roundToInt()}px/s")
        return true
    }

    /** 开始一次抛掷：复位帧计数并把 vsync 循环拉起来（不要直接写 `physMode = "throw"`） */
    private fun beginThrow() {
        physMode = "throw"
        throwFrames = 0
        throwBounces = 0
        throwStartMs = SystemClock.uptimeMillis()
        // 抛掷这几秒把主线程提到 DISPLAY 优先级：切到别的应用时进程会被降权，
        // 不抬优先级的话 vsync 回调会被饿掉（表现为"弹到一半卡一下"）。结束后复位。
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        // 先把初速推给碰撞中枢，避免下一帧被当成"中枢改过速度"而采纳成 0
        collisionMember?.let {
            it.vx = physVel[0]; it.vy = physVel[1]
            pushedVx = physVel[0]; pushedVy = physVel[1]
        }
        scheduleMotion()
    }

    // ================================================================ 设置监听
    /**
     * 设置监听：**每个 DataStore key 对应一个 watcher**，值一变就作用到窗口/引擎。
     * 这是"改设置立刻生效"的唯一实现点——设置页只负责写值，不做任何 UI→悬浮窗的直接调用。
     * 共约 30 个 watcher，全部挂在 `settingsJobs` 上，`onDestroy` 统一取消。
     */
    private fun observeSettings() {
        fun watch(
            flow: kotlinx.coroutines.flow.Flow<*>,
            apply: suspend (Any?) -> Unit,
        ) {
            settingsJobs.add(scope.launch {
                // 每个 watcher 单独兜异常：某个 key 的处理抛了不该连累其它 30 个
                try {
                    flow.collect { apply(it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.log("SVC", "watcher 失败：${e.javaClass.simpleName} ${e.message}")
                }
            })
        }
        val c = config
        watch(c.flowDouble("scale", 0.72)) { v ->
            val s = v as Double
            if (s == curScale) return@watch          // 启动时首帧回放，值没变就别乱动
            curScale = s
            applyScale(reposition = true)            // 与启动建窗同一段代码
        }
        watch(c.flowBool("window_border", false)) { v -> setBorderVisible(v as Boolean) }
        watch(c.flowInt("pet_opacity", 100)) { v -> curOpacity = (v as Int).coerceIn(10, 100); applyOpacity() }
        watch(c.flowDouble("playback_speed", PetConfig.DEFAULT_PLAYBACK_SPEED)) { v -> curSpeed = v as Double; videoView.setPlaybackSpeed(curSpeed.toFloat()) }
        watch(c.flowDouble("move_probability", 0.20)) { v ->
            engine.pActs = (1.0 - (v as Double)).coerceIn(0.1, 0.95)
        }
        watch(c.flowInt("move_min_px", 60)) { v -> engine.moveMinPx = v as Int }
        watch(c.flowInt("move_max_px", 240)) { v ->
            engine.moveMaxPx = (v as Int).coerceAtLeast(engine.moveMinPx)
        }
        watch(c.flowBool("no_move", false)) { v -> curNoMove = v as Boolean; engine.noMove = curNoMove }
        watch(c.flowBool("lock_position", false)) { v -> curLock = v as Boolean }
        watch(c.flowBool("shift_drag", false)) { v -> curShiftDrag = v as Boolean }
        watch(c.flowString("throw_strength", "standard")) { v -> curThrowStrength = v as String }
        watch(c.flowString("click_talk", "")) { v -> curClickTalk = v as String }
        watch(c.flowBool("drag_physics", false)) { v -> curPhysics = v as Boolean }
        // 重力感应：加速度计只在打开时才注册（省电），关掉即注销
        watch(c.flowBool("gravity_sensor", false)) { v ->
            curGravitySensor = v as Boolean
            setGravitySensor(curGravitySensor)
        }
        watch(c.flowBool("pet_collision", true)) { v ->
            curCollision = v as Boolean
            CollisionHub.setEnabled(curCollision)
        }
        watch(c.flowBool("island_enabled", false)) { v ->
            AppLog.log("ISLAND", "watch island_enabled=$v instance=$instanceId")
            if (instanceId == 0) {
                if (v as Boolean) ensureIsland() else stopIsland()
            }
        }
        // 灵动岛尺寸即时生效：窗口内 LaunchedEffect 已响应（Flow 已 distinct）
        watch(c.flowBool("agent_link_enabled", false)) { v ->
            if (v as Boolean) startAgentBus() else stopAgentBus()
        }
        watch(c.flowDouble("animation_gap_seconds", 0.0)) { v -> curGap = v as Double; engine.animationGapSeconds = curGap }
        watch(c.flowInt("sound_volume", 100)) { v -> curSoundVolume = (v as Int).coerceIn(0, 100) / 100f }
        watch(c.flowBool("click_sound_enabled", true)) { v -> curClickSound = v as Boolean }
        watch(c.flowBool("click_show_balance", false)) { v -> curClickBalance = v as Boolean }
        watch(c.flowBool("click_show_self_talk", false)) { v -> curClickSelfTalk = v as Boolean }
        watch(c.flowBool("self_talk_enabled", false)) { v ->
            val on = v as Boolean
            if (on == curSelfTalk) return@watch        // 首帧回放：值没变就别重排定时器
            curSelfTalk = on
            AppLog.log("SELFTALK", "开关切换 self_talk_enabled=$curSelfTalk")
            if (!curSelfTalk) {
                bubble?.hide()
                scheduleSelfTalk()          // 关掉时把已排的定时器撤掉
            } else {
                // 打开开关后 8~15s 内先来一句，用户才能确认功能真的通了
                scheduleSelfTalk(firstRun = true)
            }
        }
        watch(c.flowString("facing", "left")) { v ->
            engine.setFacing(v as String)
            videoView.setMirror(engine.shouldMirror(engine.anim ?: ""))
        }
        watch(c.flowBool("self_talk_ai", false)) { v ->
            val on = v as Boolean
            if (on == curSelfTalkAi) return@watch
            curSelfTalkAi = on
            AppLog.log("SELFTALK", "AI 生成台词=$on")
        }
        watch(c.flowBool("self_talk_ai_voice", false)) { v ->
            val on = v as Boolean
            if (on == curSelfTalkAiVoice) return@watch
            curSelfTalkAiVoice = on
            AppLog.log("SELFTALK", "台词顺便念出来=$on")
        }
        watch(c.flowStringSet("self_talk_texts", emptySet())) { v ->
            // 空集合要用内置默认兜底：DataStore 里存过空的（旧版本删光了又没有编辑入口），
            // 这里若无条件覆盖，initPet 读到的默认台词会被这一发回调清空 → "永远不说"。
            val texts = (v as Set<*>).filterIsInstance<String>().map { it.trim() }.filter { it.isNotBlank() }
            curSelfTalkTexts = texts.ifEmpty { PetConfig.defaultSelfTalkTexts().toList() }
            AppLog.log("SELFTALK", "台词更新 ${texts.size} 条（兜底后 ${curSelfTalkTexts.size} 条）")
        }
        watch(c.flowString("self_talk_bubble_style", "classic_top")) { v ->
            bubble?.bubbleStyle = v as String
        }
        watch(c.flowString("self_talk_bubble_color", "")) { v ->
            curBubbleColor = v as String
            bubble?.bubbleColor = curBubbleColor
            AppLog.log("SELFTALK", "气泡自定义颜色「$curBubbleColor」")
        }
        watch(c.flowBool("blur_enabled", false)) { v ->
            bubble?.blurOn = (v as Boolean) && Build.VERSION.SDK_INT >= 31
        }
        watch(c.flowDouble("self_talk_duration_seconds", 3.2)) { v ->
            curSelfTalkDurationMs = ((v as Double) * 1000).toLong()
        }
        watch(c.flowInt("self_talk_min_interval", 20)) { v ->
            val iv = (v as Int).coerceAtLeast(5)
            if (iv == curSelfTalkMinSec) return@watch      // 首帧回放不重排
            curSelfTalkMinSec = iv
            scheduleSelfTalk()
        }
        watch(c.flowInt("self_talk_max_interval", 60)) { v ->
            val iv = (v as Int).coerceAtLeast(curSelfTalkMinSec)
            if (iv == curSelfTalkMaxSec) return@watch
            curSelfTalkMaxSec = iv
            scheduleSelfTalk()
        }
    }

    /**
     * 应用不透明度。
     *
     * **不要再用 `LayoutParams.alpha`**：HyperOS 会把 alpha < 0.5 的悬浮窗判定为不可触摸
     * （实测 0.34 时点击全部丢失，而窗口 flags 里并没有 NOT_TOUCHABLE，说明是系统输入层拦的）。
     * 所以改成"窗口保持 alpha=1，把透明度画进内容里"：
     *  - 人物是 GLSurfaceView，`setAlpha()` 对它无效 → 走着色器 uniform（[PetVideoView.setContentAlpha]）
     *  - 波纹层 / 描边层是普通 View → 直接 setAlpha
     * 这样 10% 透明度也依然点得到桌宠。
     */
    private fun applyOpacity() {
        val lp = params ?: return
        val c = container ?: return
        val a = curOpacity / 100f
        if (lp.alpha != 1f) {
            lp.alpha = 1f
            runCatching { wm.updateViewLayout(c, lp) }
        }
        AppLog.log("WIN", "applyOpacity cur=$curOpacity a=$a lpAlpha=${lp.alpha}")
        videoView.setContentAlpha(a)
        waveView?.alpha = a
        borderView?.alpha = a
    }

    /** 调试用：显示/隐藏窗口描边 */
    private fun setBorderVisible(on: Boolean) {
        curBorder = on
        val bv = borderView
        AppLog.log("SVC", "window border=$on view=${bv != null}")
        if (bv == null) return
        bv.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            bv.label = "${engine.winW}x${engine.winH}"
            bv.alpha = curOpacity / 100f
            bv.invalidate()
        }
    }

    /** 取某段动画在某个档位下的几何（工厂每次现算，屏幕/导航条变了也自动跟随） */
    private fun geometryFor(
        anim: String?,
        scale: Double = curScale,
        haloActive: Boolean = curHaloActive,
    ): PetWindowFactory.Geometry {
        val (sw, sh) = screenPx()
        val factory = PetWindowFactory(
            screenW = sw,
            screenH = sh,
            bottomInset = navBarBottomInset(),
            frames = catalog.frames,
            globalBox = catalog.framesGlobal,
        )
        // 语音运行时在头顶预留"光环区"：窗口向上长高，人物在屏幕上的位置不变
        val halo = if (haloActive) {
            (factory.canvasWidth(scale) * HALO_CANVAS_FRAC).toInt().coerceIn(24, 220)
        } else 0
        // 收窄触摸区域用不了（Android 12+ hidden API 被 deny）时：
        //  · 窗口尺寸按"全动画最大单个包围盒"定死（fixedSize）→ 换动画**不再 resize**
        //    （之前按动画包围盒，每次切换都 resize：换动画卡顿、拖动瞬移的根因）；
        //  · 四边再撑开 TOUCH_INFLATE_PX，让双指弹弓的第二根手指能落进窗口。
        return factory.geometryFor(
            anim, scale, (2 * density).toInt(), halo,
            // **统一走并集窗口（= 一加那台的表现）**：尺寸恒定 → 换动画永不 resize →
            // 不会出现 SurfaceView 缓冲重分配/合成跳变（小米 13 上"撞边后闪"的最后一环）。
            // 代价：透明区变大，Android 12+ 又没法用 touchableRegion 收窄 → 会多吃一些点击。
            // 用户明确要求"用一加的方法"，先按这个走。
            union = windowPlan.unionWindow,
            inflate = windowPlan.inflatePx,
            fixedSize = windowPlan.fixedSize,
            // 固定尺寸只按"常用动画"算：待机/转身/走路/点击/拖拽之间切换窗口尺寸完全不变，
            // 随机大动作才偶尔 resize（那会儿用户在干别的，感觉不到）。
            coreAnims = if (this::catalog.isInitialized) {
                catalog.idles + catalog.turns + catalog.moves + catalog.clicks + listOfNotNull(catalog.drag)
            } else null,
        )
    }

    /**
     * 把几何应用到窗口 + 视频层（**唯一入口**）。
     *
     * 视频层是整张 16:9 画布，窗口只截取该动画的包围盒，所以两者尺寸不同步：
     * 窗口 = bbox 大小，视频层按 offset 平移，让 bbox 左上角正好落在窗口左上角。
     *
     * @param keepAnchor true = 保持人物脚底在屏幕上的位置不动（播放新动画/改档位时不"跳走"）
     */
    private fun applyGeometry(g: PetWindowFactory.Geometry, keepAnchor: Boolean = false) {
        // 锚点必须取「**人物包围盒**的中心 / 脚底」，不能用窗口中心：
        // 换动画时窗口四边的透明留白（inset）会变，窗口中心 ≠ 人物中心，
        // 用窗口中心当锚点 → 每次 resize 人物都被平移一段（真机表现："撞到边缘后闪到很远的地方"，
        // 只有 Android 16 这种 fixedSize + 随机动画会 resize 的机型才明显，Android 11 是 union 不变尺寸）。
        val oldPetCx = if (keepAnchor) {
            engine.winX + insetL + (engine.winW - insetL - insetR) / 2
        } else 0
        val oldPetBottom = if (keepAnchor) engine.winY + engine.winH - insetB else 0

        videoView?.let { v ->
            val vlp = (v.layoutParams as? android.widget.FrameLayout.LayoutParams)
                ?: android.widget.FrameLayout.LayoutParams(g.canvasW, g.canvasH)
            vlp.width = g.canvasW
            vlp.height = g.canvasH
            v.layoutParams = vlp
            v.translationX = g.offsetX.toFloat()
            v.translationY = g.offsetY.toFloat()
            v.requestLayout()
            // 静态兜底图（渲染坏掉时才存在）跟着走同一套几何，否则换动画/改档位后会错位
            staticFallback?.let { f ->
                runCatching {
                    f.layoutParams = android.widget.FrameLayout.LayoutParams(g.canvasW, g.canvasH)
                    f.translationX = v.translationX
                    f.translationY = v.translationY
                    f.scaleX = v.scaleX
                    f.scaleY = v.scaleY
                }
            }
        }

        engine.setWindowSize(g.winW, g.winH)

        // 碰撞/贴边按"人物实际像素"算：把窗口四边的透明留白记下来（见 insetL 的注释）
        insetL = g.insetLeft
        insetT = g.insetTop
        insetR = g.insetRight
        insetB = g.insetBottom

        // 光环圆心 = 包围盒顶边（语音运行时顶边会被 haloPx 往下推，所以每帧几何都要同步过去）
        waveView?.setHeadAnchor(g.headTopPx)

        val lp = params
        val c = container
        if (c != null && lp != null) {
            val sizeChanged = lp.width != g.winW || lp.height != g.winH
            lp.width = g.winW
            lp.height = g.winH
            // 什么时候必须 relayout：
            //  - 用了 touchableRegion：**每次都要**，否则区域不会重算（它只在 relayout 时刷新）；
            //  - 没用（Android 12+ hidden API 被 deny，例如 HyperOS/Android 16）：
            //    只有尺寸真的变了才 relayout —— 否则每次换动画都白跑一趟布局，白白掉帧。
            if (touchRegionSupported || sizeChanged) {
                runCatching { wm.updateViewLayout(c, lp) }
            }
            syncTouchWindow(relayout = true)
        }

        if (keepAnchor) {
            // 窗口高度会随动画/光环变化，锚点换算后可能顶出屏幕 → 按当前边界收一下
            val xr = windowXRange()
            val yr = windowYRange()
            // 新窗口位置 = 让"人物包围盒"的中心/脚底落回原来的屏幕位置
            val newPetCx = g.insetLeft + (g.winW - g.insetLeft - g.insetRight) / 2
            val newPetBottom = g.winH - g.insetBottom
            engine.setPosition(
                (oldPetCx - newPetCx).coerceIn(xr.first, xr.last),
                (oldPetBottom - newPetBottom).coerceIn(yr.first, yr.last),
            )
        }
        borderView?.label = "${g.winW}x${g.winH}"
        // 只打 logcat，不写文件日志：换动画是高频事件，主线程写文件会拖慢手势（拖动起手那一下）
        android.util.Log.i(
            "Dsh_SVC",
            "geometry anim=${g.anim} canvas=${g.canvasW}x${g.canvasH} win=${g.winW}x${g.winH} halo=${g.haloPx}" +
                    " inset=${g.insetLeft},${g.insetTop},${g.insetRight},${g.insetBottom}" +
                    " off=${g.offsetX},${g.offsetY} pos=${engine.winX},${engine.winY} petCx=${engine.winX + g.insetLeft + (g.winW - g.insetLeft - g.insetRight) / 2}",
        )
    }

    /**
     * **唯一的"按当前档位应用几何"入口**。启动建窗、改档位、换动画都走这里，
     * 只是参数来源不同（启动/改档位用 `curScale`，满屏档位传负值）。
     *
     * @param reposition true = 重新落位到屏幕正中；false = 保持人物脚底不动
     */
    private fun applyScale(reposition: Boolean) {
        applyGeometry(geometryFor(engine.anim), keepAnchor = !reposition)
        syncEngineScreenBounds()
        if (reposition) {
            val (x, y) = restPosition()
            engine.setPosition(x, y)
        }
        AppLog.log("SVC", "applyScale scale=$curScale win=${engine.winW}x${engine.winH} reposition=$reposition")
    }



    fun showBubble(text: String, durationMs: Long = 6000) {
        uiHandler.post { bubble?.showText(text, durationMs) }
    }

    /**
     * 给**短命 UI**（悬浮菜单）用的落盘入口：跑在服务作用域上，UI 销毁也不会被取消。
     * 菜单里的"播放速度 / 大小 / 拖动物理 / 锁定位置"以前用自己的 rememberCoroutineScope，
     * 菜单一关就被取消 → 改了没效果。现在统一走这里。
     */
    fun persist(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /** 菜单 / 测试页 / 设置页共用的参数入口（功能层，见 [PetControlHub]） */
    val controls: PetControlHub by lazy { PetControlHub(config, scope) }

    // ================================================================ 菜单
    /** 待执行的"摘窗口"任务：见 [removeMenu] 的注释 */
    private var menuRemoveRunnable: Runnable? = null

    private fun openMenu() {
        // 上一次的延后移除要取消，否则新菜单会被一起摘掉
        menuRemoveRunnable?.let { uiHandler.removeCallbacks(it) }
        menuRemoveRunnable = null
        val old = menuWindow
        menuWindow = null
        if (old != null) uiHandler.postDelayed({ runCatching { old.dismiss() } }, 80)
        menuWindow = PetMenu(
            service = this,
            engine = engine,
            onDismiss = { removeMenu() },
        ).apply { show() }
    }

    fun removeMenu() {
        val w = menuWindow ?: return
        menuWindow = null
        // **不要**在点击回调里同步移除窗口：菜单项的 Compose 涟漪（RenderNodeAnimator）刚
        // `startPending` 就被 detach → `IllegalStateException: Cannot start this animator on a detached view!`
        // （2026-10-26 真机崩溃，整个进程被杀）。推后 ~80ms 再摘，让涟漪正常起来/结束。
        val r = Runnable { runCatching { w.dismiss() } }
        menuRemoveRunnable = r
        uiHandler.postDelayed(r, 80)
    }

    /** 立刻摘菜单（服务销毁时用：这时不能再等 80ms，handler 马上就被清掉了） */
    private fun removeMenuNow() {
        menuRemoveRunnable?.let { runCatching { uiHandler.removeCallbacks(it) } }
        menuRemoveRunnable = null
        val w = menuWindow
        menuWindow = null
        runCatching { w?.dismiss() }
    }

    /**
     * 退出平台：**先把菜单收掉，再停服务**。
     *
     * 以前菜单里直接调 [quit]（= `stopSelf()`），服务一停 `onDestroy` 就把 handler/窗口一起带走，
     * 结果菜单窗口留在屏幕上不消失（用户反馈"点了退出菜还在"）。
     */
    fun quitSoon() {
        uiHandler.postDelayed({ runCatching { quit() } }, 250)
    }

    // ================================================================ 其他功能
    /** 多开：找下一个空闲槽位（[SLOT_COUNT] 个空壳服务类之一），带超量保护与"自动错位"起始坐标。 */
    fun spawnPet() {
        scope.launch {
            val active = activeInstanceIds()
            val cap = maxInstances
            if (cap > 0 && active.size >= cap) {
                showBubble("最多同时 $cap 只小肥鱼～", 3000)
                return@launch
            }
            if (active.size >= SLOT_COUNT) {
                showBubble("已达槽位上限（$SLOT_COUNT 只）～", 3000)
                return@launch
            }
            // 复用最小空闲 id
            val id = (1 until SLOT_COUNT).firstOrNull { it !in active } ?: return@launch
            PetOverlayService.ensureRunning(this@PetOverlayService, id)
            showBubble("一只新的小肥鱼诞生啦！", 4000)
        }
    }

    fun openSettings() {
        MainActivity.start(this)
    }

    // 悬浮 AI 对话窗口（长按菜单入口）
    private var chatWindow: PetChatWindow? = null

    fun openChat() {
        if (chatWindow == null) chatWindow = PetChatWindow(this)
        chatWindow?.show()
    }

    private fun dismissChat() {
        chatWindow?.dismiss()
        chatWindow = null
    }

    fun showBalanceInBubble() {
        scope.launch {
            val apiKey = config.chatApiKey()
            if (apiKey.isBlank()) {
                showBubble("未配置 AI API Key，无法查询余额", 6000)
                return@launch
            }
            showBubble("让我看看余额…", 6000)
            val r = withContext(Dispatchers.IO) {
                Balance.fetch(config.chatBaseUrl(), apiKey, config.chatVerifySsl(), config.chatTimeout())
            }
            r.fold(
                onSuccess = { txt ->
                    // 余额峰谷提示（上游 v4.0.4）：北京时间峰谷档位 + 下一切换时间
                    val tier = Balance.pricingTierText()
                    showBubble("$txt\n$tier", 6500)
                    // 余额分档动画（上游 v4.0.4）：数值可得时按档位播动画
                    val num = Regex("¥([0-9.]+)").find(txt)?.groupValues?.get(1)?.toDoubleOrNull()
                    if (num != null) {
                        val tierIndex = Balance.tierIndexFor(num)
                        val anim = Balance.TIER_ANIMS.getOrNull(tierIndex)
                        if (anim != null && engine.hasAnim(anim)) {
                            engine.switch(anim)
                        }
                    }
                },
                onFailure = { showBubble("余额查询失败：${it.message}", 7000) },
            )
        }
    }

    fun checkUpdate() {
        scope.launch {
            showBubble("正在检查更新…", 6000)
            val r = withContext(Dispatchers.IO) { Updater.latestRelease() }
            r.fold(
                onSuccess = { rel ->
                    val current = com.dshpet.android.BuildConfig.VERSION_NAME
                    if (Updater.isNewer(rel.tag, current)) {
                        showBubble("发现新版本 v${rel.tag}（当前 $current）。可前往设置-关于下载更新。", 9000)
                    } else {
                        showBubble("已经是最新版本（$current）啦", 6000)
                    }
                },
                onFailure = { showBubble("检查更新失败：${it.message}", 7000) },
            )
        }
    }

    /** 显示/隐藏：隐藏时暂停视频解码与全部定时器（CPU≈0），显示时从当前动画恢复。 */
    fun toggleVisible() {
        visible = !visible
        container?.visibility = if (visible) View.VISIBLE else View.GONE
        touchView?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) videoView.resumePlay() else videoView.pausePlay()
        bubble?.hide()
    }

    /** 回到右下角（菜单项；启动时的默认位置是屏幕正中，见 [restPosition]） */
    fun returnToCorner() {
        engine.cancelMove()
        val (x, y) = cornerPosition()
        engine.setPosition(x, y)
        savePosition()
    }

    /** 彻底退出：把所有实例一起停掉、清 `pet_running` 标记，避免下次冷启动"幽灵重启"。 */
    fun quit() {
        // 用户明确不要了 → 停看门狗，否则 15 分钟后又被拉起来
        wantRunning = false
        runCatching { PetWatchdogJob.cancel(this) }
        scope.launch { runCatching { config.setPetRunning(false) } }
        // 仅退出本实例（其它小肥鱼不受影响）
        stopSelf()
    }

    /**
     * 用户从最近任务里划掉。
     *
     * 国内 ROM（HyperOS/MIUI）在这之后常常直接 stop 掉服务，`START_STICKY` 不一定会重启。
     * 这里补一发 1 秒后的闹钟，等于"自己把自己拉回来"——只有用户确实还想要桌宠时才拉。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val want = wantRunning
        AppLog.log("SVC", "onTaskRemoved instance=$instanceId 想运行=$want")
        if (!want) return
        runCatching {
            val am = getSystemService(android.app.AlarmManager::class.java) ?: return
            // 注意：`intent(this, PetOverlayService::class.java)` 在自己类里会撞名，
            // 这里用伴生对象里的 `intent(ctx, id)` 构造。
            val pi = PendingIntent.getForegroundService(
                this, 1001 + instanceId, PetOverlayService.intent(this, instanceId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1500, pi)
        }.onFailure { AppLog.log("SVC", "onTaskRemoved 自愈排期失败：${it.javaClass.simpleName}") }
    }

    // ================================================================ 位置持久化
    private fun savePosition() {
        scope.launch {
            stateStore?.save(PetState.State(engine.winX, engine.winY, engine.facing))
        }
    }

    private fun persistFacing(f: String) {
        scope.launch { config.setFacing(f) }
    }

    // ================================================================ 音效
    private fun setupSound() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            soundPool = SoundPool.Builder().setMaxStreams(3).build()
        } else {
            @Suppress("DEPRECATION")
            soundPool = SoundPool(3, android.media.AudioManager.STREAM_MUSIC, 0)
        }
        clickSoundId = try {
            val afd = assets.openFd("pet/sounds/click.wav")
            soundPool?.load(afd, 1) ?: 0
        } catch (e: Exception) { 0 }
    }

    internal var curSoundVolume = 1f

    private fun playClickSound() {
        if (clickSoundId != 0) {
            val v = curSoundVolume
            soundPool?.play(clickSoundId, v, v, 1, 0, 1f)
        }
    }

    // ================================================================ 通知
    /**
     * 常驻通知（FGS 必须有一个）：两个动作「AI 对话 / 退出」，点正文回设置页。
     * 每只桌宠用 `1000 + instanceId` 做通知 id，多开时互不覆盖。
     */
    private fun buildNotification(): Notification {
        val openSettings = PendingIntent.getActivity(
            this, 0, MainActivity.startIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openChat = PendingIntent.getActivity(
            this, 1, Intent(this, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .applyHideRecentsPolicy(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this, 2, intent(this, instanceId).setAction("quit"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(if (instanceId == 0) "dsh-pet 桌宠" else "dsh-pet 桌宠 #$instanceId")
            .setContentText("桌宠正在陪伴你 · 点击打开设置")
            .setContentIntent(openSettings)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "AI 对话", openChat)
            .addAction(0, "退出", quit)
            .build()
        return n
    }

    // ================================================================ 工具
    /** 屏幕物理像素（整块屏幕，含系统栏） */
    /** 屏幕尺寸：统一走 [com.dshpet.android.util.ScreenMetrics]（以前和另外 5 个类各抄一份） */
    private fun screenPx(): Pair<Int, Int> = com.dshpet.android.util.ScreenMetrics.screenPx(this)

    /** 导航条（手势条）占掉的底部高度，本机实测 44px */
    private fun navBarBottomInset(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val w = getSystemService(WINDOW_SERVICE) as WindowManager
            w.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            val res = resources
            val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) res.getDimensionPixelSize(id) else 0
        }
    } catch (e: Throwable) {
        0
    }

    // ---- 屏幕尺寸缓存（拖动热路径专用，见 [cachedScreenW]）----
    /** 屏幕宽（缓存；未初始化时走一次 binder） */
    private fun screenW(): Int {
        if (cachedScreenW <= 0) refreshScreenCache()
        return cachedScreenW
    }

    /** 屏幕高（缓存） */
    private fun screenH(): Int {
        if (cachedScreenH <= 0) refreshScreenCache()
        return cachedScreenH
    }

    /** 导航条高度（缓存） */
    private fun cachedNavInsetSafe(): Int {
        if (cachedNavInset < 0) refreshScreenCache()
        return cachedNavInset
    }

    /**
     * 刷新屏幕尺寸缓存。
     * 只在初始化与 `onConfigurationChanged`（旋转 / 分屏 / 折叠 / 分辨率切换）时调用，
     * **不要**放进拖动热路径——那里每次都会跨进程问 WindowManager（见 [cachedScreenW]）。
     */
    private fun refreshScreenCache() {
        val (w, h) = screenPx()
        cachedScreenW = w
        cachedScreenH = h
        cachedNavInset = navBarBottomInset()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { refreshScreenCache() }
        AppLog.log("SVC", "配置变化：屏幕 ${cachedScreenW}x$cachedScreenH 导航条=$cachedNavInset")
    }

    /**
     * 真正"能放桌宠"的底边：扣掉导航条（手势条）。
     * 窗口现在等于人物包围盒，窗口底边就是人物的脚，落在导航条里就会被系统栏挡住。
     */
    private fun usableBottom(): Int = screenH() - cachedNavInsetSafe()

    /**
     * 默认落点：**屏幕正中央**。
     *
     * 居中的是**人物像素**而不是窗口：窗口比鱼身大一圈（insetL/R/T/B），
     * 直接按窗口居中会让鱼身整体偏一点。
     */
    private fun restPosition(): Pair<Int, Int> {
        val x = (screenW() - engine.winW - insetL + insetR) / 2
        val y = (screenH() - engine.winH - insetT + insetB) / 2
        return x.coerceAtLeast(0) to y.coerceAtLeast(0)
    }

    /**
     * 右下角落点（菜单「回到右下角」用）。
     *
     * 边界一律来自 [windowXRange]/[windowYRange]（= 人物像素贴边 + 右下各 10% 溢出），
     * 所以"点了回到右下角"和"手动拖到右下角"落点完全一致，也不会因为窗口有透明留白而离角老远。
     */
    private fun cornerPosition(): Pair<Int, Int> =
        windowXRange().last to windowYRange().last

    // ---- 边界 ----
    // 一律按**人物实际像素**算（insetL/T/R/B 是窗口里的透明留白）：
    //  - 左/上：鱼身边缘正好贴 0（窗口允许负坐标，因为负数那部分只是透明留白）；
    //  - 右：鱼身最多再超出屏幕 [CORNER_OVERFLOW_FRAC] × **人物像素宽**（贴角时自然压出一点）；
    //  - 下：**不再往下沉**——像素脚底正好落在"可用区下沿"（有导航条时就是导航条上沿）。
    //    2026-10-26 用户反馈"还是掉下去太多"：以前下沿也放 10% 溢出（按窗口算时更多），
    //    满屏档位会沉下去 60px+；现在归零。
    private fun visibleW(): Int = (engine.winW - insetL - insetR).coerceAtLeast(1)
    private fun visibleH(): Int = (engine.winH - insetT - insetB).coerceAtLeast(1)
    private fun overflowX(): Int = (visibleW() * CORNER_OVERFLOW_FRAC).toInt()

    /** 人物像素底边在屏幕坐标里的 y（= 窗口底 - 底部透明留白） */
    private fun pixelBottom(): Int = engine.winY + engine.winH - insetB

    /** 人物像素右边在屏幕坐标里的 x */
    private fun pixelRight(): Int = engine.winX + engine.winW - insetR

    /** 窗口水平可移动范围：让**鱼身**左贴 0、右可溢出 */
    private fun windowXRange(): IntRange {
        val end = screenW() - engine.winW + insetR + overflowX()
        return -insetL..maxOf(-insetL, end)
    }

    /** 窗口垂直可移动范围：上贴 0、下沿正好停在可用区下沿（不下沉） */
    private fun windowYRange(): IntRange {
        val end = usableBottom() - engine.winH + insetB
        return -insetT..maxOf(-insetT, end)
    }

    /**
     * 同步引擎屏幕边界（自动散步范围）。
     *
     * 同样按人物像素算，但**不含**右下那 10% 溢出（溢出只给"手动拖动/回到右下角"）：
     * 否则桌宠自己散步时也会往屏幕外走，脚被导航条挡住、看起来像沉下去了。
     */
    private fun syncEngineScreenBounds() {
        engine.screenLeft = -insetL
        engine.screenRight = screenW() + insetR
        engine.screenTop = -insetT
        engine.screenBottom = usableBottom() + insetB
    }

    fun requireOverlayPermission(): Boolean = Settings.canDrawOverlays(this)
}
