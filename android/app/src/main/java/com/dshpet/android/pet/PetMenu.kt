package com.dshpet.android.pet

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshpet.android.data.PetConfig
import com.dshpet.android.util.ProvideComposeHost
import com.dshpet.android.util.attachComposeHost
import com.dshpet.android.util.mdBlur
import kotlin.math.roundToInt

/**
 * 长按桌宠弹出的 MD3 菜单（独立悬浮窗，Compose 渲染）。
 *
 * 位置：`PetOverlayService.onLongPress()` → `openMenu()` → 本类。
 * 分组：对话 / 动画集 / 播放速度 / 大小 / 物理 / 余额 / 更新 / 多开 / 快捷启动 / 设置 / 退出。
 * 菜单本身**不持有状态**：点了就写 [com.dshpet.android.data.PetConfig]，
 * 再由 `PetOverlayService.observeSettings()` 里的 watcher 应用到悬浮窗。
 */
class PetMenu(
    private val service: PetOverlayService,
    private val engine: PetEngine,
    private val onDismiss: () -> Unit,
) {
    private val ctx: Context = service
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    /** 上次定位用的坐标：和它相同就不再 updateViewLayout，避免"布局改动→再次布局"的抖动 */
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    fun show() {
        if (view != null) return
        val composeView = ComposeView(ctx).apply {
            attachComposeHost()
            setContent { ProvideComposeHost { MenuRoot() } }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }
        view = composeView
        params = lp
        composeView.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismiss(); true
            } else false
        }
        composeView.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismiss(); true
            } else false
        }
        runCatching { wm.addView(composeView, lp) }
        // 菜单定位：桌宠上方（放不下放下方）。
        // 用 layout 回调而不是 post{}：Compose 首帧的 measuredWidth 还是 0，
        // 那时候算出来的位置是错的，而且之后菜单尺寸一变（改缩放）也不会重新定位。
        composeView.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            val bw = r - l
            val bh = b - t
            if (bw <= 0 || bh <= 0) return@addOnLayoutChangeListener
            if (bw == or - ol && bh == ob - ot && lp.x == lastX && lp.y == lastY) return@addOnLayoutChangeListener
            val (sw, sh) = screenPx()
            val petCx = engine.winX + engine.winW / 2
            val x = (petCx - bw / 2).coerceIn(8, maxOf(8, sw - bw - 8))
            var y = engine.winY - bh - 12
            if (y < 0) y = engine.winY + engine.winH + 12
            y = y.coerceIn(0, maxOf(0, sh - bh))
            lp.x = x
            lp.y = y
            lastX = x
            lastY = y
            runCatching { wm.updateViewLayout(v, lp) }
        }
    }

    fun dismiss() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /** 悬浮窗整体拖动（标题栏手势回调） */
    private fun onWindowDrag(dx: Float, dy: Float) {
        val p = params ?: return
        val v = view ?: return
        p.x += dx.toInt()
        p.y += dy.toInt()
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** 屏幕尺寸：统一走 [com.dshpet.android.util.ScreenMetrics]，别再抄一份实现 */
    private fun screenPx(): Pair<Int, Int> = com.dshpet.android.util.ScreenMetrics.screenPx(ctx)

    // ================================================================ UI
    @Composable
    private fun MenuRoot() {
        val cfg = PetConfig.get(ctx)
        // 菜单缩放（设置-外观可调 0.7x..1.4x，紧凑布局默认即小菜单）
        val menuScale by cfg.flowDouble("menu_scale", 1.0).collectAsState(initial = 1.0)
        val scaleF = menuScale.toFloat()
        // 当前大小档位（与设置页共用 PetControlHub.SCALE_PRESETS 这一份来源）
        val curScale by cfg.flowDouble("scale", 0.72).collectAsState(initial = 0.72)
        // 屏幕尺寸（dp）：菜单不能超过屏幕，否则大字体/小屏上会贴着边甚至溢出
        val conf = LocalConfiguration.current
        val menuW = minOf(236.0 * scaleF, conf.screenWidthDp * 0.62, 320.0).dp
        val menuMaxH = (conf.screenHeightDp * 0.72).dp
        // 诊断：菜单实际用了多大（屏幕自适应/缩放都靠这行核对，不用去量窗口像素）
        androidx.compose.runtime.LaunchedEffect(menuW, menuMaxH) {
            com.dshpet.android.util.AppLog.log(
                "MENU",
                "菜单宽 ${menuW.value.roundToInt()}dp（236×${scaleF}=${(236 * scaleF).roundToInt()}dp，" +
                        "屏宽×0.62=${(conf.screenWidthDp * 0.62).roundToInt()}dp，上限 320dp）" +
                        " 最高 ${menuMaxH.value.roundToInt()}dp（屏高×0.72），屏 ${conf.screenWidthDp}×${conf.screenHeightDp}dp",
            )
        }
        var speedOpen by remember { mutableStateOf(false) }
        var sizeOpen by remember { mutableStateOf(false) }
        var animHubOpen by remember { mutableStateOf(false) }
        var quickOpen by remember { mutableStateOf(false) }
        var noMove by remember { mutableStateOf(engine.noMove) }
        var lock by remember { mutableStateOf(service.curLock) }
        var physics by remember { mutableStateOf(service.curPhysics) }
        val blurCfg by cfg.flowBool("blur_enabled", false).collectAsState(initial = false)
        val blurOn = blurCfg && Build.VERSION.SDK_INT >= 31
        fun run(action: suspend () -> Unit) {
            // 用服务级作用域：onDismiss() 会立刻移除窗口 → ComposeView 被 dispose →
            // rememberCoroutineScope 取消，之前写在这里的 cfg.setXxx() 全被掐掉（菜单设置无效的根因）
            service.persist { action() }
            onDismiss()
        }

        Surface(
            // 缩放只做一次：宽度按 scaleF 算，**不要**再叠 graphicsLayer 缩放——
            // 之前两者同时存在，实际视觉宽度 = 236 × scaleF²，且 transformOrigin(0,0)
            // 从左上角放大，窗口（WRAP_CONTENT）却按布局尺寸算，于是菜单看起来"被拉伸/被裁"。
            modifier = Modifier
                .width(menuW)
                .heightIn(max = menuMaxH)
                .mdBlur(blurOn, radius = 22),
            shape = RoundedCornerShape(16.dp),
            color = if (blurOn) Color(0xE6FFFFFF) else MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            tonalElevation = 2.dp,
        ) {
            // 外层 Column **不滚动**：标题栏（"小肥鱼" + 关闭按钮）固定在最上面，
            // 用户往下翻菜单时它不会跟着走（以前整块都在 verticalScroll 里，
            // 想关菜单得先滑回顶部，很别扭）。
            Column {
                // 标题栏：整条可拖动窗口，右侧直接关闭
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onWindowDrag(dragAmount.x, dragAmount.y)
                            }
                        }
                        .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Menu, contentDescription = "拖动",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(18.dp),
                    )
                    Text(
                        "小肥鱼",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp),
                    )
                    IconButton(onClick = { dismiss() }, modifier = Modifier.height(28.dp)) {
                        Icon(
                            Icons.Filled.Close, contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(16.dp),
                        )
                    }
                }   // ← 标题栏结束（固定，不随内容滚动）
                // 内容区：只有这里滚动
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                if (animHubOpen) {
                    AnimHub(onBack = { animHubOpen = false }) { name -> run { engine.switch(name) } }
                } else if (quickOpen) {
                    QuickLaunchList(cfg, onBack = { quickOpen = false }) { pkg ->
                        run {
                            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                            if (intent != null) runCatching { ctx.startActivity(intent) }
                        }
                    }
                } else {
                    // ---- 互动 ----
                    // 点了"直接执行"的项，执行完就把菜单收起来（以前要再点一次叉，很烦）。
                    // 会展开二级菜单的项（动画集/播放速度/大小/快捷启动）保持展开，不在这里收。
                    MenuGroup("互动")
                    MenuItem(Icons.AutoMirrored.Filled.Send, "AI 对话") { run { service.openChat() }; onDismiss() }
                    MenuItem(Icons.Filled.Star, "欧鲸鲸（彩蛋）") { run { EasterEggPopup.showRandom(ctx) }; onDismiss() }
                    // ---- 播放 ----
                    MenuGroup("播放")
                    MenuItem(Icons.Filled.PlayArrow, "动画集", badge = "91 段") { animHubOpen = true }
                    MenuItem(Icons.Filled.Refresh, "播放速度", badge = "${service.curSpeed}×") { speedOpen = true }
                    SpeedSubmenu(speedOpen) { v -> service.controls.setPlaybackSpeed(v); onDismiss() }
                    MenuItem(Icons.Filled.Home, "大小", badge = PetControlHub.labelOf(curScale)) { sizeOpen = true }
                    SizeSubmenu(sizeOpen, curScale) { v -> service.controls.setScale(v); onDismiss() }
                    // ---- 功能 ----
                    MenuGroup("功能")
                    ToggleItem(Icons.Filled.Build, "拖动物理", physics) {
                        physics = !physics; service.controls.setDragPhysics(physics); onDismiss()
                    }
                    MenuItem(Icons.Filled.Home, "回到右下角") { run { service.returnToCorner() }; onDismiss() }
                    ToggleItem(Icons.Filled.Close, "不移动", noMove) {
                        noMove = !noMove; service.controls.setNoMove(noMove); onDismiss()
                    }
                    ToggleItem(Icons.Filled.Lock, "锁定位置", lock) {
                        lock = !lock; service.controls.setLockPosition(lock); onDismiss()
                    }
                    MenuItem(Icons.Filled.Add, "生小肥鱼（多开）") { run { service.spawnPet() }; onDismiss() }
                    MenuItem(Icons.Filled.Star, "灵动岛") { run { service.toggleIsland() }; onDismiss() }
                    // ---- 工具 ----
                    MenuGroup("工具")
                    MenuItem(Icons.Filled.Star, "DeepSeek 余额") { run { service.showBalanceInBubble() }; onDismiss() }
                    MenuItem(Icons.Filled.Refresh, "检查更新") { run { service.checkUpdate() }; onDismiss() }
                    MenuItem(Icons.Filled.PlayArrow, "快捷启动") { quickOpen = true }
                    // ---- 设置 ----
                    MenuGroup("设置")
                    MenuItem(Icons.Filled.Settings, "桌宠设置") { run { service.openSettings() }; onDismiss() }
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    // ---- 退出 ----
                    MenuItem(Icons.Filled.Close, "退出桌宠", danger = true) { onDismiss(); service.quitSoon() }
                }
            }
        }
        }
    }

    @Composable
    private fun MenuGroup(title: String) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 1.dp),
        )
    }

    @Composable
    private fun MenuItem(
        icon: ImageVector,
        title: String,
        badge: String? = null,
        danger: Boolean = false,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(18.dp),
            )
            Text(
                text = title,
                fontSize = 13.sp,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
            )
            badge?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun ToggleItem(icon: ImageVector, title: String, checked: Boolean, onToggle: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
            Text(title, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp).weight(1f))
            Switch(checked = checked, onCheckedChange = { onToggle() }, modifier = Modifier.height(24.dp))
        }
    }

    @Composable
    private fun SpeedSubmenu(open: Boolean, onPick: (Double) -> Unit) {
        if (!open) return
        listOf(1.0, 1.25, 1.5, 1.75, 2.0).forEach { v ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(v) }
                    .padding(start = 36.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
            ) {
                Text("${v}×", fontSize = 12.sp)
                if (service.curSpeed == v) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
                }
            }
        }
    }

    /**
     * 大小档位子菜单：**遍历 [PetControlHub.SCALE_PRESETS]**，不再自带一套档位/文案，
     * 选中态按配置里的 `scale` 值判断（以前拿 `engine.winW == 640*v` 猜，换了动画 winW 就变，永远对不上）。
     */
    @Composable
    private fun SizeSubmenu(open: Boolean, curScale: Double, onPick: (Double) -> Unit) {
        if (!open) return
        PetControlHub.SCALE_PRESETS.forEach { preset ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(preset.value) }
                    .padding(start = 36.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
            ) {
                Text(preset.label, fontSize = 12.sp)
                if (PetControlHub.isSameScale(curScale, preset.value)) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
                }
            }
        }
    }

    @Composable
    private fun AnimHub(onBack: () -> Unit, onPick: (String) -> Unit) {
        val all = engine.idles + engine.turns + engine.moves + engine.clicks + engine.acts
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBack)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("◀ 返回", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text("动画集（点击播放）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
            val grouped = all.groupBy { name ->
                when (name) {
                    in engine.idles -> "待机"
                    in engine.turns -> "转向"
                    in engine.moves -> "移动"
                    in engine.clicks -> "点击回应"
                    in engine.acts -> "随机动作"
                    else -> "其他"
                }
            }
            for ((group, names) in grouped) {
                MenuGroup(group)
                names.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(name) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Text(name, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    @Composable
    private fun QuickLaunchList(cfg: PetConfig, onBack: () -> Unit, onPick: (String) -> Unit) {
        var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        androidx.compose.runtime.LaunchedEffect(Unit) { apps = cfg.quickLaunch() }
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBack)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("◀ 返回", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text("快捷启动", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
            if (apps.isEmpty()) {
                Text("（在桌宠设置中添加应用）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            apps.forEach { (pkg, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(pkg) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(label, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }

}
