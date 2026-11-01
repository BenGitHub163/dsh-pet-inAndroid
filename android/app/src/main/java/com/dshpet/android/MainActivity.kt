package com.dshpet.android

/**
 * 设置页（Jetpack Compose + Material 3）——用户唯一需要打开的界面。
 *
 * 底部六个页签：**常规 / 桌宠 / 外观 / AI 对话 / 快捷 / 关于**。
 * 页面本身几乎不存状态，所有值都来自 [com.dshpet.android.data.PetConfig] 的 DataStore
 * （`collectAsState` 订阅），改动则立刻写回 —— 因此设置是"改了就生效"，
 * 真正把它应用到悬浮窗的是 `PetOverlayService.observeSettings()` 里那批 watcher。
 *
 * 两个容易踩的点（都已处理）：
 *  - 音色 / Resource-Id 这类语音配置**必须写 PetConfig**（DataStore），只写 VoiceSettings
 *    会被 `VoiceRuntime.buildSettings()` 用 PetConfig 覆盖掉——这就是"音色改不了"的根因；
 *  - 桌面图标有两个 `activity-alias`（LauncherNormal / LauncherHidden），
 *    本类 `exported=false`，只能通过别名启动，切换"隐藏后台"时会重建别名。
 */

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.dshpet.android.data.PetConfig
import com.dshpet.android.data.applyHideRecentsPolicy
import com.dshpet.android.pet.PetOverlayService
import com.dshpet.android.pet.Updater
import com.dshpet.android.util.mdBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置主界面（MD3）。对应桌面端"桌宠设置"各页：常规 / 桌宠行为 / 外观 / AI 对话 / 快捷启动 / 关于。
 * 所有改动即时写入 DataStore，桌宠服务实时生效；"隐藏后台"通过切换
 * LauncherNormal/LauncherHidden 两个 activity-alias 实现（android:excludeFromRecents）。
 */
/**
 * 改了音色 / Resource-Id 之后，正在跑的语音必须重建才会用新的声音。
 * 原因：`VoiceLoop.buildTts()` 只在 `start()` 时调用一次，运行中改配置不会重新装配。
 */
private fun restartVoiceIfRunning() {
    runCatching {
        if (com.dshpet.android.voice.core.VoiceLoop.running) {
            com.dshpet.android.voice.core.VoiceRuntime.stop()
            com.dshpet.android.util.AppLog.u("语音", "音色/资源已更新，语音会话已停止（下次开口用新音色）")
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SettingsScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.dshpet.android.pet.PetOverlayService.uiForeground = true
        // 每次回到本页 +1：常规页据此重新查"系统自启动白名单"——
        // 用户从系统设置页授权回来时，开关要立刻反映真实状态
        resumeTick.intValue++
    }

    override fun onPause() {
        super.onPause()
        com.dshpet.android.pet.PetOverlayService.uiForeground = false
    }

    // 注意：悬浮窗在本页前台时**仍然可点**——不要在 onResume 里把桌宠设成不可触摸，
    // 那样 App 内就完全点不到它了（曾试过，代价太大）。


    companion object {
        fun startIntent(ctx: Context): Intent =
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .applyHideRecentsPolicy()

        fun start(ctx: Context) {
            ctx.startActivity(startIntent(ctx))
        }

        /** 切换"隐藏后台"：动态启用/禁用两个 launcher alias（excludeFromRecents） */
        fun applyRecentsAlias(ctx: Context, hide: Boolean) {
            val pm = ctx.packageManager
            val normal = ComponentName(ctx, "$PACKAGE.LauncherNormal")
            val hidden = ComponentName(ctx, "$PACKAGE.LauncherHidden")
            fun set(comp: ComponentName, enabled: Boolean) {
                pm.setComponentEnabledSetting(
                    comp,
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            set(normal, !hide)
            set(hidden, hide)
        }
    }
}

private const val PACKAGE = "com.dshpet.android"

/**
 * 页面「回到前台」计数器。
 *
 * 悬浮窗/系统权限这类"在别的页面里改完回来才生效"的状态，不能只靠 DataStore 的 Flow：
 * `onResume` 时 +1，需要的 Composable 用 `LaunchedEffect(resumeTick)` 重新查一次真实状态。
 */
private val resumeTick = mutableIntStateOf(0)

// ================================================================ 页面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val cfg = PetConfig.get(ctx)
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("常规", "桌宠", "外观", "AI 对话", "快捷", "关于", "测试")
    val blurCfg by cfg.flowBool("blur_enabled", false).collectAsState(initial = false)
    val blurOn = blurCfg && com.dshpet.android.compat.Platform.blurSupported

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("dsh-pet 桌宠", fontWeight = FontWeight.SemiBold)
                        Text("Android 版 v${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    androidx.compose.material3.TextButton(onClick = {
                        ctx.startActivity(
                            android.content.Intent(ctx, com.dshpet.android.voice.ui.PetSettingsActivity::class.java)
                        )
                    }) { androidx.compose.material3.Text("语音设置", fontSize = 13.sp) }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                // 7 个标签：FilterChip 一屏放不下（最小宽度约 64dp × 7 > 393dp），
                // 改成等宽文字标签（weight=1），保证「测试」不用滑动也能点到。
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp)) {
                    tabs.forEachIndexed { i, t ->
                        val sel = tab == i
                        Text(
                            t,
                            fontSize = 12.sp,
                            maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (sel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .clickable { tab = i }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .mdBlur(blurOn, radius = 24)
        ) {
            when (tab) {
                0 -> GeneralTab(ctx, cfg, scope, resumeTick.intValue)
                1 -> BehaviorTab(cfg, scope)
                2 -> AppearanceTab(ctx, cfg, scope)
                3 -> AiTab(ctx, cfg, scope)
                4 -> QuickLaunchTab(ctx, cfg, scope)
                5 -> AboutTab(ctx, cfg, scope)
                else -> TestTab(ctx, cfg, scope)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Card(shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            subtitle?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) = SettingRow(title, subtitle, trailing = { Switch(checked, onToggle) })

// ================================================================ 常规
@Composable
private fun GeneralTab(
    ctx: android.content.Context,
    cfg: PetConfig,
    scope: kotlinx.coroutines.CoroutineScope,
    resumeTick: Int = 0,
) {
    val running by cfg.flowBool("pet_running", false).collectAsState(initial = false)
    val overlayGranted by cfg.flowBool("overlay_permission_granted", false).collectAsState(initial = false)
    val autoStartWant by cfg.flowBool("autostart", false).collectAsState(initial = false)
    val hideRecents by cfg.flowBool("hide_from_recents", true).collectAsState(initial = true)
    val batteryOpt by cfg.flowBool("battery_optimization", false).collectAsState(initial = false)

    // ---- 开机自启 = 用户意愿 + 系统「自启动白名单」真实权限 ----
    // 系统白名单读不到时是 null（非 MIUI 系 / 隐藏 API 被拦），此时只能按用户意愿显示
    var autoStartGranted by remember { mutableStateOf<Boolean?>(null) }
    /** 上一次同步给系统的接收器状态（避免每次 onResume 都写一次组件状态） */
    var receiverSynced by remember { mutableStateOf<Boolean?>(null) }
    /** 本次进页面是否已经提醒过"系统没允许自启动"（避免每次 onResume 都弹） */
    var autoStartWarned by remember { mutableStateOf(false) }
    // key 里必须带 autoStartWant：DataStore 的 flow 首帧先给 initial=false，
    // 只按 resumeTick 触发的话，等真值（比如 true）回流时不会再跑一次，接收器就会被误关掉。
    LaunchedEffect(resumeTick, autoStartWant) {
        val g = com.dshpet.android.compat.autostart.AutoStartHelper.queryAutoStart(ctx)
        autoStartGranted = g
        // 接收器只跟"用户开关"走：白名单没允许时系统本来就不会拉起我们，
        // 但把接收器关掉反而会导致"后来在系统里授权了却永远收不到开机广播"。
        val desired = autoStartWant
        if (desired != receiverSynced) {
            com.dshpet.android.compat.autostart.AutoStartHelper.setBootReceiverEnabled(ctx, desired)
            receiverSynced = desired
        }
        com.dshpet.android.util.AppLog.u("桌宠", "开机自启：开关=${if (autoStartWant) "开" else "关"}，系统白名单=${com.dshpet.android.compat.autostart.AutoStartHelper.grantedText(g)}")
        // 开了开关但系统里没允许：每次进设置页提醒一次（MIUI 覆盖安装会把自启动重置掉）
        if (autoStartWant && g == false && !autoStartWarned) {
            autoStartWarned = true
            android.widget.Toast.makeText(
                ctx,
                "系统还没允许「小肥鱼」自启动（小米覆盖安装后常被重置），点下面的「去设置」打开",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    // **开关只表示"用户意愿"**，不再被系统状态压回去：
    // 之前 `want && granted` 的写法会导致"在系统里授权了、但查询仍返回 ignore 时开关自动弹回"，
    // 用户看到的就是"点了没反应"（真机上 MIUI 覆盖安装也会把 op 10021 重置成 ignore）。
    // 系统真实状态改到下面一行文字里显示，两者分开。
    val autoStartShown = autoStartWant

    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = Settings.canDrawOverlays(ctx)
        scope.launch { cfg.setOverlayPermission(granted) }
        if (granted) PetOverlayService.ensureRunning(ctx)
    }

    // 悬浮窗授权 / 本页回到前台时：同步 launcher alias（接收器由上面的 LaunchedEffect 负责）
    LaunchedEffect(overlayGranted, resumeTick, hideRecents) {
        if (!overlayGranted) return@LaunchedEffect
        MainActivity.applyRecentsAlias(ctx, hideRecents)
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("桌宠") {
            SwitchRow("开启桌宠", if (running) "悬浮窗服务运行中" else "桌宠未启动", running) { on ->
                if (on) {
                    if (!Settings.canDrawOverlays(ctx)) {
                        scope.launch {
                            overlayLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$PACKAGE"))
                            )
                        }
                    } else {
                        PetOverlayService.ensureRunning(ctx)
                    }
                } else {
                    PetOverlayService.stopAll(ctx)
                }
            }
            SettingRow(
                "悬浮窗权限",
                if (overlayGranted) "已授予（桌宠可显示在任意应用上）" else "未授予，桌宠无法显示",
                trailing = {
                    TextButton(onClick = {
                        overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$PACKAGE")))
                    }) { Text(if (overlayGranted) "重新申请" else "去授权") }
                },
            )
        }
        Section("后台行为") {
            // 开关 = 用户意愿 且 系统「自启动白名单」允许；没权限时点它会被送去系统设置页
            val needGuide = com.dshpet.android.compat.autostart.AutoStartHelper.needsGuide()
            SwitchRow(
                "开机自启",
                buildString {
                    append("重启手机后自动显示桌宠｜系统自启：")
                    append(com.dshpet.android.compat.autostart.AutoStartHelper.grantedText(autoStartGranted))
                },
                autoStartShown,
            ) { on ->
                if (on) {
                    scope.launch { cfg.setAutoStart(true) }
                    val g = autoStartGranted
                    // 接收器不因"白名单未允许"而关掉：否则以后在系统里授权了也收不到开机广播
                    com.dshpet.android.compat.autostart.AutoStartHelper.setBootReceiverEnabled(ctx, true)
                    if (g == false) {
                        // 系统没允许：先把人送去「自启动管理」，回来后 onResume 会重新查权限，
                        // 授权成功那一下开关才会真的变成"开"（没给权限就保持原样）
                        android.widget.Toast.makeText(
                            ctx, "先去系统里把「小肥鱼」的自启动打开，回来这里会自动变成开",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        com.dshpet.android.compat.autostart.AutoStartHelper.guide(ctx)
                    }
                } else {
                    scope.launch { cfg.setAutoStart(false) }
                    com.dshpet.android.compat.autostart.AutoStartHelper.setBootReceiverEnabled(ctx, false)
                }
            }
            // 小米/红米等 ROM 默认拦第三方自启：光开上面的开关没用，系统压根不会拉起本应用
            if (needGuide) {
                SettingRow(
                    "系统自启动白名单",
                    "${com.dshpet.android.compat.autostart.AutoStartHelper.romName()} 当前：${
                        com.dshpet.android.compat.autostart.AutoStartHelper.grantedText(autoStartGranted)
                    }。未允许时系统不会拉起本应用；覆盖安装后这一项常被重置，需要重新打开",
                    trailing = {
                        TextButton(onClick = { com.dshpet.android.compat.autostart.AutoStartHelper.guide(ctx) }) {
                            Text("去设置")
                        }
                    },
                )
            }
            SwitchRow(
                "隐藏后台",
                "应用不显示在最近任务列表；切换后设置页会自动重启以立即生效",
                hideRecents,
            ) { on ->
                scope.launch {
                    cfg.setHideFromRecents(on)
                    PetConfig.hideRecentsCached = on
                    MainActivity.applyRecentsAlias(ctx, on)
                    // 任务在最近任务中的可见性创建时已固定，必须重建任务：
                    // 关闭旧任务 → 以正确标志重启设置页
                    val act = ctx as? android.app.Activity
                    if (act != null && !act.isFinishing) {
                        act.finishAndRemoveTask()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            runCatching {
                                val i = Intent(act, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (on) i.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                                act.applicationContext.startActivity(i)
                            }
                        }, 350)
                    }
                }
            }
            SwitchRow(
                "忽略电池优化",
                if (batteryOpt) "已允许后台运行" else "建议开启，防止系统杀后台",
                batteryOpt,
            ) { on ->
                scope.launch { cfg.setBatteryOpt(on) }
                if (on) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$PACKAGE"))
                        )
                    }
                }
            }
        }
        Section("启动") {
            SettingRow(
                "立即启动桌宠",
                "若已授予悬浮窗权限，此操作会立即显示桌宠",
                trailing = {
                    Button(onClick = { PetOverlayService.ensureRunning(ctx) }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.width(16.dp))
                        Text("启动")
                    }
                },
            )
        }
    }
}

// ================================================================ 桌宠行为
@Composable
private fun BehaviorTab(cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val noMove by cfg.flowBool("no_move", false).collectAsState(initial = false)
    val lock by cfg.flowBool("lock_position", false).collectAsState(initial = false)
    val shiftDrag by cfg.flowBool("shift_drag", false).collectAsState(initial = false)
    val physics by cfg.flowBool("drag_physics", false).collectAsState(initial = false)
    val speed by cfg.flowDouble("playback_speed", PetConfig.DEFAULT_PLAYBACK_SPEED).collectAsState(initial = PetConfig.DEFAULT_PLAYBACK_SPEED)
    val gap by cfg.flowDouble("animation_gap_seconds", 0.0).collectAsState(initial = 0.0)
    val maxInstances by cfg.flowInt("max_instances", 4).collectAsState(initial = 4)
    val moveProb by cfg.flowDouble("move_probability", 0.20).collectAsState(initial = 0.20)
    val moveMinPx by cfg.flowInt("move_min_px", 60).collectAsState(initial = 60)
    val moveMaxPx by cfg.flowInt("move_max_px", 240).collectAsState(initial = 240)
    val clickSound by cfg.flowBool("click_sound_enabled", true).collectAsState(initial = true)
    val clickBalance by cfg.flowBool("click_show_balance", false).collectAsState(initial = false)
    val clickSelfTalk by cfg.flowBool("click_show_self_talk", false).collectAsState(initial = false)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("动作与移动") {
            SwitchRow("自动移动", "桌宠会朝面向方向散步（概率与距离可调）", !noMove) { on ->
                scope.launch { cfg.setNoMove(!on) }
            }
            // 多开上限（0 = 无限制；硬件槽位 10）
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    "多开上限",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "同时存在的最大小肥鱼数量（0 = 无限制，最多 ${com.dshpet.android.pet.PetOverlayService.SLOT_COUNT} 只）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 窄屏可横向滑动（LazyRow）
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(listOf(1, 2, 3, 4, 6, 8, 10, 0)) { v ->
                        androidx.compose.material3.FilterChip(
                            selected = maxInstances == v,
                            onClick = {
                                scope.launch { cfg.setMaxInstances(v) }
                                com.dshpet.android.pet.PetOverlayService.maxInstances = v
                            },
                            label = { Text(if (v == 0) "不限" else "$v") },
                        )
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("移动概率", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = moveProb.toFloat(), onValueChange = { scope.launch { cfg.setMoveProbability(it.toDouble()) } },
                    valueRange = 0f..0.9f,
                    modifier = Modifier.weight(1f),
                )
                Text("${(moveProb * 100).roundToInt()}%", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("散步距离", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = moveMinPx.toFloat(), onValueChange = { scope.launch { cfg.setMoveRange(it.toInt(), maxOf(it.toInt(), moveMaxPx)) } },
                    valueRange = 10f..600f,
                    modifier = Modifier.weight(1f),
                )
                Text("${moveMinPx}px", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("最大距离", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = moveMaxPx.toFloat(), onValueChange = { scope.launch { cfg.setMoveRange(minOf(moveMinPx, it.toInt()), it.toInt()) } },
                    valueRange = 30f..1200f,
                    modifier = Modifier.weight(1f),
                )
                Text("${moveMaxPx}px", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            SwitchRow("锁定位置", "桌宠固定不动，点击互动仍有效", lock) { on -> scope.launch { cfg.setLockPosition(on) } }
            SwitchRow("仅长按可拖动", "需先长按桌宠才能拖动（触摸版 SHIFT 拖拽）", shiftDrag) { on ->
                scope.launch { cfg.setShiftDrag(on) }
            }
            SwitchRow("拖动物理", "松手抛出：惯性、重力、反弹、地面摩擦", physics) { on ->
                scope.launch { cfg.setDragPhysics(on) }
            }
            // 重力感应：用加速度计决定"下"在哪（手机怎么拿，桌宠就往哪边掉）
            val gravitySensor by cfg.flowBool("gravity_sensor", false).collectAsState(initial = false)
            SwitchRow(
                "重力感应",
                "用手机传感器判断\"下\"在哪：手机竖着/横着/倒着拿，桌宠都会往真实的下方掉。" +
                        "需要开着「拖动物理」；关掉后重力只作用于被甩出去的那一下（不会自己往下滑）",
                gravitySensor,
            ) { on -> scope.launch { cfg.setGravitySensor(on) } }
            val collision by cfg.flowBool("pet_collision", true).collectAsState(initial = true)
            SwitchRow("多开碰撞", "小肥鱼们相撞会弹开（鱼塘碰碰车）", collision) { on ->
                scope.launch { cfg.setCollisionEnabled(on) }
            }
            // 甩出/弹弓力度档位（上游 PR#33）
            val throwStrength by cfg.flowString("throw_strength", "standard").collectAsState(initial = "standard")
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text("甩出力度", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("拖拽中按住第二根手指进入弹弓蓄力，松手发射", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("gentle" to "轻柔", "standard" to "标准", "strong" to "强力", "crazy" to "疯狂").forEach { (id, label) ->
                        FilterChip(
                            selected = throwStrength == id,
                            onClick = { scope.launch { cfg.setThrowStrength(id) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("播放速度", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = speed.toFloat(), onValueChange = { scope.launch { cfg.setPlaybackSpeed(it.toDouble()) } },
                    valueRange = 0.5f..2.0f, steps = 5,
                    modifier = Modifier.weight(1f),
                )
                Text("${speed}×", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("动画间隔", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = gap.toFloat(),
                    onValueChange = { v -> scope.launch { cfg.setAnimGap((v * 10).roundToInt() / 10.0) } },
                    valueRange = 0f..10f,
                    steps = 99,     // 0.1s 一档：滑杆不再吐出 3.7000000000000002 这种值
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%.1fs".format(gap),
                    Modifier.width(44.dp), fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
        Section("点击互动") {
            SwitchRow("点击音效", "点击桌宠时的 Q 弹音效", clickSound) { on -> scope.launch { cfg.setClickSound(on) } }
            // 音量（上游 v4.0.5）
            val soundVolume by cfg.flowInt("sound_volume", 100).collectAsState(initial = 100)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("音效音量", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = soundVolume.toFloat(), onValueChange = { scope.launch { cfg.setSoundVolume(it.toInt()) } },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                )
                Text("$soundVolume%", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            // 点击台词绑定（上游 v4.1.0）
            val clickTalk by cfg.flowString("click_talk", "").collectAsState(initial = "")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("点击台词", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                OutlinedTextField(
                    value = clickTalk,
                    onValueChange = { if (it.length <= 60) scope.launch { cfg.setClickTalk(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("点击桌宠时冒出的台词（留空关闭）", fontSize = 12.sp) },
                )
            }
            SwitchRow("点击显示余额", "点击同时查询 DeepSeek 余额", clickBalance) { on -> scope.launch { cfg.setClickShowBalance(on) } }
            SwitchRow("点击自言自语", "点击时随机显示一条自言自语", clickSelfTalk) { on -> scope.launch { cfg.setClickShowSelfTalk(on) } }
        }
    }
}

// ================================================================ 外观
@Composable
private fun AppearanceTab(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    // 外观页拆成 4 段（原来一整块 376 行）：每段自己读需要的配置，父函数只负责排版。
    Column(Modifier.verticalScroll(rememberScrollState())) {
        AppearanceScaleSection(cfg, scope)
        AppearanceEffectSection(cfg, scope)
        AppearanceIslandSection(cfg, scope)
        AppearanceSelfTalkSection(ctx, cfg, scope)
    }
}


/**
 * 外观页「大小与透明度」段。从 [AppearanceTab] 里拆出来，只读自己用到的配置项。
 */
@Composable
private fun AppearanceScaleSection(cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val scale by cfg.flowDouble("scale", 0.72).collectAsState(initial = 0.72)
    val menuScale by cfg.flowDouble("menu_scale", 1.0).collectAsState(initial = 1.0)
    val opacity by cfg.flowInt("pet_opacity", 100).collectAsState(initial = 100)
    val facing by cfg.flowString("facing", "left").collectAsState(initial = "left")

    Section("大小与透明度") {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 档位与文案来自唯一来源（PetControlHub.SCALE_PRESETS），悬浮菜单用的是同一份
            com.dshpet.android.pet.PetControlHub.SCALE_PRESETS.forEach { preset ->
                FilterChip(
                    selected = com.dshpet.android.pet.PetControlHub.isSameScale(scale, preset.value),
                    onClick = { scope.launch { cfg.setScale(preset.value) } },
                    label = { Text(preset.label) },
                )
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("长按菜单", Modifier.width(90.dp), fontSize = 13.sp)
            Slider(
                value = menuScale.toFloat(), onValueChange = { scope.launch { cfg.setMenuScale(it.toDouble()) } },
                valueRange = 0.7f..1.4f, steps = 6,
                modifier = Modifier.weight(1f),
            )
            Text("${"%.1f".format(menuScale)}×", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("不透明度", Modifier.width(90.dp), fontSize = 13.sp)
            Slider(
                value = opacity.toFloat(), onValueChange = { scope.launch { cfg.setPetOpacity(it.toInt()) } },
                valueRange = 10f..100f,
                modifier = Modifier.weight(1f),
            )
            Text("$opacity%", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("left" to "朝左", "right" to "朝右").forEach { (v, label) ->
                FilterChip(selected = facing == v, onClick = { scope.launch { cfg.setFacing(v) } }, label = { Text(label) })
            }
        }
        // 「满屏」= 用所有动画的并集包围盒算出的最大画幅（PetWindowFactory.fitCanvasWidth）
        if (scale < 0) {
            Text(
                "满屏：按全部 91 段动画的最大画幅放大，人物会尽量占满屏幕；" +
                        "个别特效动画（放烟花等）会铺满大半个屏幕，此时会挡住那块区域的点击。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 外观页「效果」段。从 [AppearanceTab] 里拆出来，只读自己用到的配置项。
 */
@Composable
private fun AppearanceEffectSection(cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val blur by cfg.flowBool("blur_enabled", false).collectAsState(initial = false)

    Section("效果") {
        SwitchRow(
            "毛玻璃效果",
            if (com.dshpet.android.compat.Platform.blurSupported) "菜单 / 聊天窗 / 设置面板背景模糊（默认关）"
            else "当前系统版本（<Android 12）不支持硬件模糊，将回退为半透明",
            blur,
        ) { on -> scope.launch { cfg.setBlur(on) } }
    }
}

/**
 * 外观页「灵动岛」段。从 [AppearanceTab] 里拆出来，只读自己用到的配置项。
 */
@Composable
private fun AppearanceIslandSection(cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val islandEnabled by cfg.flowBool("island_enabled", false).collectAsState(initial = false)
    val islandW by cfg.flowInt("island_width_pct", 50).collectAsState(initial = 50)
    val islandH by cfg.flowInt("island_height_dp", 54).collectAsState(initial = 54)
    val islandStyle by cfg.flowString("island_style", "dark").collectAsState(initial = "dark")
    val islandEmoji by cfg.flowString("island_emoji", "🐳").collectAsState(initial = "🐳")
    val islandText by cfg.flowString("island_text", "").collectAsState(initial = "")

    Section("灵动岛") {
        SwitchRow("显示灵动岛", "胶囊悬浮窗：时间/自定义文本；点击切换桌宠显示/隐藏，可拖动、顶部吸附", islandEnabled) { on ->
            scope.launch { cfg.setIslandEnabled(on) }
        }
        Column(Modifier.padding(horizontal = 12.dp)) {
            Text("风格", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("dark" to "深色", "light" to "浅色", "glass" to "玻璃").forEach { (id, label) ->
                    FilterChip(
                        selected = islandStyle == id,
                        onClick = { scope.launch { cfg.setIslandStyle(id) } },
                        label = { Text(label) },
                    )
                }
            }
        }
        // 宽/高滑杆（即时生效）
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("宽度", Modifier.width(90.dp), fontSize = 13.sp)
            Slider(
                value = islandW.toFloat(), onValueChange = { scope.launch { cfg.setIslandWidthPct(it.toInt()) } },
                valueRange = 30f..90f,
                modifier = Modifier.weight(1f),
            )
            Text("${islandW}%", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("高度", Modifier.width(90.dp), fontSize = 13.sp)
            Slider(
                value = islandH.toFloat(), onValueChange = { scope.launch { cfg.setIslandHeightDp(it.toInt()) } },
                valueRange = 36f..96f,
                modifier = Modifier.weight(1f),
            )
            Text("${islandH}dp", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("图标", Modifier.width(90.dp), fontSize = 13.sp)
            OutlinedTextField(
                value = islandEmoji, onValueChange = { if (it.length <= 4) scope.launch { cfg.setIslandEmoji(it) } },
                modifier = Modifier.width(90.dp),
                singleLine = true,
            )
            Spacer(Modifier.width(12.dp))
            Text("文本", Modifier.width(40.dp), fontSize = 13.sp)
            OutlinedTextField(
                value = islandText, onValueChange = { if (it.length <= 16) scope.launch { cfg.setIslandText(it) } },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("自定义短文本", fontSize = 12.sp) },
            )
        }
    }
}

/**
 * 外观页「自言自语气泡」段。从 [AppearanceTab] 里拆出来，只读自己用到的配置项。
 */
@Composable
private fun AppearanceSelfTalkSection(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    // 这段原来是 257 行的一大坨；按"开关 / 间隔 / 颜色 / 样式"拆成四块，每块自己读需要的配置。
    Section("自言自语气泡") {
        SelfTalkSwitchBlock(ctx, cfg, scope)
        SelfTalkIntervalBlock(cfg, scope)
        SelfTalkColorBlock(cfg, scope)
        SelfTalkStyleBlock(cfg, scope)
    }
}

/**
 * 自言自语「开关与台词入口」块（从 [AppearanceSelfTalkSection] 拆出来）。
 */
@Composable
private fun SelfTalkSwitchBlock(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val selfTalk by cfg.flowBool("self_talk_enabled", false).collectAsState(initial = false)
    val stAi by cfg.flowBool("self_talk_ai", false).collectAsState(initial = false)
    val stAiVoice by cfg.flowBool("self_talk_ai_voice", false).collectAsState(initial = false)

    SwitchRow("允许自言自语", "随机间隔冒出可爱小气泡", selfTalk) { on -> scope.launch { cfg.setSelfTalk(on) } }
    // 台词来源：内置台词（快、稳定）还是让 DeepSeek 现场写一句（更像活的、不会复读）
    SwitchRow(
        "用 AI 生成台词",
        "配了 DeepSeek Key 就现场写一句（提示词已限制 18 字以内）；没 Key 或失败自动用下面的台词",
        stAi,
    ) { on -> scope.launch { cfg.setSelfTalkAi(on) } }
    SwitchRow(
        "顺便念出来",
        "生成后同时用豆包语音读一遍（需要豆包 Key；正在语音对话时不插嘴）",
        stAiVoice,
    ) { on -> scope.launch { cfg.setSelfTalkAiVoice(on) } }
    // 台词管理：**逐条**增删的页面（15 个格子，一行一句），入口放在这里。
    // 以前是"一整块多行文本框 + 一次性保存"，用户反馈输入方式不对（改到中间就乱了、也没法单独删一条）。
    val talkLines by cfg.flowStringSet("self_talk_texts", emptySet()).collectAsState(initial = emptySet())
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("台词", fontSize = 13.sp)
            Text(
                if (talkLines.isEmpty()) "当前用内置默认的 5 句"
                else "当前 ${talkLines.size} 条（桌宠随机挑一条说）",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = {
                ctx.startActivity(
                    android.content.Intent(ctx, com.dshpet.android.pet.SelfTalkTextsActivity::class.java)
                )
            },
            modifier = Modifier.height(38.dp),
        ) { Text("管理台词", fontSize = 12.sp) }
    }
}

/**
 * 自言自语「随机间隔与停留」块（从 [AppearanceSelfTalkSection] 拆出来）。
 */
@Composable
private fun SelfTalkIntervalBlock(cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val stMin by cfg.flowInt("self_talk_min_interval", 20).collectAsState(initial = 20)
    val stMax by cfg.flowInt("self_talk_max_interval", 60).collectAsState(initial = 60)
    val stDur by cfg.flowDouble("self_talk_duration_seconds", 3.2).collectAsState(initial = 3.2)

    // ---- 随机间隔：多久 ~ 多久之间随机冒一次 ----
    // 拖杆期间只改本地草稿，松手才落盘（否则每一帧都写 DataStore + 服务侧重排定时器）
    val minSec = stMin.coerceAtLeast(5)
    val maxSec = stMax.coerceAtLeast(minSec)
    var minEdit by remember { mutableStateOf(minSec.toFloat()) }
    var maxEdit by remember { mutableStateOf(maxSec.toFloat()) }
    LaunchedEffect(stMin) { minEdit = stMin.toFloat() }
    LaunchedEffect(stMax) { maxEdit = stMax.toFloat() }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("随机间隔", Modifier.width(90.dp), fontSize = 13.sp)
            Text(
                "${minEdit.toInt()} ~ ${maxEdit.toInt()} 秒之间随机",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最短", Modifier.width(44.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = minEdit.coerceIn(5f, maxEdit.coerceAtLeast(6f)),
                onValueChange = { minEdit = it },
                onValueChangeFinished = { scope.launch { cfg.setSelfTalkMin(minEdit.toInt().coerceAtLeast(5)) } },
                valueRange = 5f..maxEdit.coerceAtLeast(6f),
                modifier = Modifier.weight(1f),
            )
            Text("${minEdit.toInt()}s", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最长", Modifier.width(44.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = maxEdit.coerceIn(minEdit.coerceAtLeast(5f), 600f),
                onValueChange = { maxEdit = it },
                onValueChangeFinished = { scope.launch { cfg.setSelfTalkMax(maxEdit.toInt().coerceAtLeast(minSec)) } },
                valueRange = minEdit.coerceAtLeast(5f)..600f,
                modifier = Modifier.weight(1f),
            )
            Text("${maxEdit.toInt()}s", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
    // ---- 气泡停留时间：冒出来之后显示多久 ----
    var durEdit by remember { mutableStateOf(stDur.toFloat()) }
    LaunchedEffect(stDur) { durEdit = stDur.toFloat() }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("停留", Modifier.width(90.dp), fontSize = 13.sp)
        Slider(
            value = durEdit.coerceIn(1f, 15f),
            onValueChange = { durEdit = it },
            onValueChangeFinished = { scope.launch { cfg.setSelfTalkDuration(durEdit.toDouble().coerceIn(1.0, 15.0)) } },
            valueRange = 1f..15f,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${"%.1f".format(durEdit)}s",
            Modifier.width(44.dp), fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }

}

/**
 * 自言自语「气泡颜色」块（从 [AppearanceSelfTalkSection] 拆出来）。
 */
@Composable
private fun SelfTalkColorBlock(cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    // 颜色块原来 109 行：把「自定义取色弹窗」再拆出去（它自己带一堆 slider 状态）
    val bubbleColor by cfg.flowString("self_talk_bubble_color", "").collectAsState(initial = "")
    var colorPicker by remember { mutableStateOf(false) }
    // 统一写入口：写配置 + 打一行日志（排查"颜色没保存"时直接看 logcat 有没有这行）
    val applyBubbleColor: (String) -> Unit = { hex ->
        scope.launch { cfg.setSelfTalkBubbleColor(hex) }
        com.dshpet.android.util.AppLog.u("桌宠", "气泡颜色 → " + (hex.ifBlank { "跟随样式" }))
    }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "气泡颜色",
                Modifier.padding(start = 4.dp, top = 4.dp),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // 把当前值直接显示出来：这样"到底存没存"一眼就能看到
                if (bubbleColor.isBlank()) "  当前：跟随样式" else "  当前：$bubbleColor",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ColorSwatch(argb = 0xFFFFFFFF.toInt(), selected = bubbleColor.isBlank(), label = "跟随") {
                applyBubbleColor("")
            }
            listOf(
                0xF2FFFFFF.toInt() to "白",
                0xF2D6E6FF.toInt() to "蓝",
                0xF2DFF7EF.toInt() to "薄荷",
                0xF2FFE4EC.toInt() to "樱粉",
                0xF2FFF8E1.toInt() to "奶黄",
                0xF2EDE7FF.toInt() to "淡紫",
                0xE6F1F3F5.toInt() to "玻璃",
                0xF2303136.toInt() to "深色",
            ).forEach { (argb, label) ->
                ColorSwatch(
                    argb = argb,
                    selected = bubbleColor.equals("#%02X%02X%02X%02X".format(
                        (argb ushr 24) and 0xFF, (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF,
                    ), ignoreCase = true),
                    label = label,
                ) {
                    applyBubbleColor(
                        "#%02X%02X%02X%02X".format(
                            (argb ushr 24) and 0xFF, (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF,
                        )
                    )
                }
            }
            OutlinedButton(onClick = { colorPicker = true }, modifier = Modifier.height(34.dp)) {
                Text("自定义…", fontSize = 12.sp)
            }
        }
    }
    if (colorPicker) {
        SelfTalkColorPickerDialog(
            initial = bubbleColor,
            onPick = { hex -> applyBubbleColor(hex); colorPicker = false },
            onReset = { applyBubbleColor(""); colorPicker = false },
            onDismiss = { colorPicker = false },
        )
    }
}

/**
 * 气泡自定义取色的弹窗（ARGB 四个滑杆 + 预览）。
 * 从 [SelfTalkColorBlock] 拆出来：它自己维护 4 个 slider 状态，混在一起读不动。
 */
@Composable
private fun SelfTalkColorPickerDialog(
    initial: String,
    onPick: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val init = com.dshpet.android.pet.parseArgb(initial) ?: 0xF2FFFFFF.toInt()
    var aEdit by remember { mutableStateOf(((init ushr 24) and 0xFF).toFloat()) }
    var rEdit by remember { mutableStateOf(((init shr 16) and 0xFF).toFloat()) }
    var gEdit by remember { mutableStateOf(((init shr 8) and 0xFF).toFloat()) }
    var bEdit by remember { mutableStateOf((init and 0xFF).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("气泡颜色", fontSize = 16.sp) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(androidx.compose.ui.graphics.Color(aEdit.toInt(), rEdit.toInt(), gEdit.toInt(), bEdit.toInt()))
                        .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                )
                ColorSliderRow("不透明", aEdit) { aEdit = it }
                ColorSliderRow("红", rEdit) { rEdit = it }
                ColorSliderRow("绿", gEdit) { gEdit = it }
                ColorSliderRow("蓝", bEdit) { bEdit = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPick("#%02X%02X%02X%02X".format(aEdit.toInt(), rEdit.toInt(), gEdit.toInt(), bEdit.toInt()))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onReset) { Text("跟随样式") } },
    )
}

/**
 * 自言自语「气泡样式」块（从 [AppearanceSelfTalkSection] 拆出来）。
 */
@Composable
private fun SelfTalkStyleBlock(cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val bubbleStyle by cfg.flowString("self_talk_bubble_style", "classic_top").collectAsState(initial = "classic_top")

    // 气泡样式：MD3 FilterChip 单选组 + 颜色预览
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text(
            "气泡样式",
            Modifier.padding(start = 4.dp, top = 4.dp),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "classic_top" to 0xFFFFFFFF,
                "soft_blue_top" to 0xFFD6E6FF,
                "glass_right" to 0xCCFFFFFF,
                "paper_left" to 0xFFFFF8E1,
                "breath_bubble" to 0xFFE8F5FF,
            ).forEach { (id, color) ->
                FilterChip(
                    selected = bubbleStyle == id,
                    onClick = { scope.launch { cfg.setSelfTalkBubbleStyle(id) } },
                    label = { Text(styleLabel(id)) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .padding(start = 2.dp)
                                .size(14.dp)
                                .background(
                                    androidx.compose.ui.graphics.Color(color),
                                    androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                )
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outline,
                                    androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                )
                        )
                    },
                )
            }
        }
    }
}

private fun styleLabel(s: String): String = when (s) {
    "classic_top" -> "经典白"
    "soft_blue_top" -> "软萌蓝"
    "glass_right" -> "玻璃右侧"
    "paper_left" -> "纸片左侧"
    "breath_bubble" -> "呼吸圆泡"
    else -> s
}

/** 气泡颜色色块（点一下就把该颜色写进 `self_talk_bubble_color`） */
@Composable
private fun ColorSwatch(argb: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(androidx.compose.ui.graphics.Color(argb))
                .border(
                    width = if (selected) 2.dp else 0.5.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(8.dp),
                )
        )
        Text(
            label,
            fontSize = 10.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 自定义颜色弹窗里的一行：标签 + 0..255 滑杆 + 数值 */
@Composable
private fun ColorSliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), fontSize = 12.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
        )
        Text(
            value.toInt().toString(),
            Modifier.width(36.dp),
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

// ================================================================ AI 对话
@Composable
private fun AiTab(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val agentLink by cfg.flowBool("agent_link_enabled", false).collectAsState(initial = false)
    val name by cfg.flowString("chat_provider_name", "DeepSeek").collectAsState(initial = "DeepSeek")
    val baseUrl by cfg.flowString("chat_base_url", "https://api.deepseek.com").collectAsState(initial = "https://api.deepseek.com")
    val chatPath by cfg.flowString("chat_chat_path", "/v1/chat/completions").collectAsState(initial = "/v1/chat/completions")
    val model by cfg.flowString("chat_model", "deepseek-chat").collectAsState(initial = "deepseek-chat")
    val apiKey by cfg.flowString("chat_api_key", com.dshpet.android.data.KeyFactory.defaults().deepseek).collectAsState(initial = com.dshpet.android.data.KeyFactory.defaults().deepseek)
    val temperature by cfg.flowDouble("chat_temperature", 0.7).collectAsState(initial = 0.7)
    val maxTokens by cfg.flowInt("chat_max_tokens", 2048).collectAsState(initial = 2048)
    val verifySsl by cfg.flowBool("chat_verify_ssl", true).collectAsState(initial = true)
    val timeout by cfg.flowInt("chat_timeout", 60).collectAsState(initial = 60)
    val models by cfg.flowStringSet("model_list", emptySet()).collectAsState(initial = emptySet())
    var modelSyncing by remember { mutableStateOf(false) }
    var modelSyncMsg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        // 这个安装包没内置 Key（朋友版）：顶部说清楚，别让人以为是软件坏了
        if (!com.dshpet.android.data.KeyFactory.hasBuiltInDeepseek && apiKey.isBlank()) {
            androidx.compose.material3.Card(
                Modifier.fillMaxWidth().padding(12.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("这个安装包没有内置 API Key", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Text(
                        "AI 对话 / 自言自语 / 语音都需要你自己的 Key：\n" +
                                "① 在下面「DeepSeek 对话」填 sk-… \n" +
                                "② 在「语音设置」里填豆包（火山）Key\n" +
                                "两样都填好之后重启一次桌宠即可。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        Section("Agent 联动（插件）") {
            SwitchRow(
                "监听 Agent 事件",
                "读取事件目录下的 *.jsonl，AI Agent 状态联动桌宠动画/气泡",
                agentLink,
            ) { on -> scope.launch { cfg.setAgentLinkEnabled(on) } }

            // 事件目录授权（Android 11+ 存储限制：SAF 或「所有文件访问」二选一）
            var eventSource by remember { mutableStateOf(com.dshpet.android.plugin.AgentEventBus.describeSource(ctx)) }
            val safLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    com.dshpet.android.plugin.AgentEventBus.treeUri = uri
                    scope.launch { cfg.setAgentEventsTreeUri(uri.toString()) }
                    eventSource = com.dshpet.android.plugin.AgentEventBus.describeSource(ctx)
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("事件目录", fontSize = 13.sp)
                Text(
                    eventSource,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Button(onClick = { safLauncher.launch(null) }) { Text("选择目录") }
                    OutlinedButton(onClick = {
                        // 已授权：直接启用公共目录
                        val it = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + ctx.packageName),
                        )
                        runCatching { ctx.startActivity(it) }
                    }) { Text("所有文件访问") }
                }
            }
        }
        Section("服务配置（OpenAI 兼容）") {
            OutlinedTextField(name, { scope.launch { cfg.setChatProviderName(it) } }, label = { Text("服务商名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            OutlinedTextField(baseUrl, { scope.launch { cfg.setChatBaseUrl(it) } }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            OutlinedTextField(chatPath, { scope.launch { cfg.setChatPath(it) } }, label = { Text("聊天接口路径") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            // 模型：手动输入 + 同步列表选择（上游 v4.1.0 API/Provider 列表）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    model,
                    { scope.launch { cfg.setChatModel(it) } },
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.OutlinedButton(
                    enabled = !modelSyncing && apiKey.isNotBlank(),
                    onClick = {
                        modelSyncing = true
                        modelSyncMsg = null
                        scope.launch {
                            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.dshpet.android.chat.fetchModels(baseUrl, apiKey, verifySsl)
                            }
                            r.fold(
                                onSuccess = { list ->
                                    cfg.setModelList(list)
                                    modelSyncMsg = "已同步 ${list.size} 个模型"
                                },
                                onFailure = { modelSyncMsg = "同步失败：${it.message}" },
                            )
                            modelSyncing = false
                        }
                    },
                ) { Text(if (modelSyncing) "同步中…" else "同步模型") }
            }
            if (apiKey.isBlank()) {
                Text(
                    "填写 API Key 后可同步模型列表",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            modelSyncMsg?.let {
                Text(
                    it, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (models.isNotEmpty()) {
                Text(
                    "选择模型（${models.size}）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp),
                )
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    items(models.sorted()) { m ->
                        androidx.compose.material3.FilterChip(
                            selected = m == model,
                            onClick = { scope.launch { cfg.setChatModel(m) } },
                            label = { Text(m, fontSize = 11.sp) },
                        )
                    }
                }
            }
            OutlinedTextField(apiKey, { if (it.isNotBlank()) scope.launch { cfg.setChatApiKey(it) }
                                    // 同时写穿到语音层：文本与语音共用同一把 Key
                                    runCatching {
                                        val vs = com.dshpet.android.voice.core.VoiceSettingsStore(ctx)
                                        val cur = vs.load()
                                        if (cur.deepseekKey != it) vs.save(cur.copy(deepseekKey = it))
                                    } }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("温度", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(value = temperature.toFloat(), onValueChange = { v -> scope.launch { cfg.setChatTemperature(Math.round(v * 10f) / 10.0) } }, valueRange = 0f..2f, modifier = Modifier.weight(1f))
                Text("%.1f".format(temperature), Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("最大 Tokens", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(value = maxTokens.toFloat(), onValueChange = { scope.launch { cfg.setChatMaxTokens(it.toInt()) } }, valueRange = 256f..8192f, steps = 30, modifier = Modifier.weight(1f))
                Text("$maxTokens", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            SwitchRow("跳过 SSL 证书验证", "本地网关/自签名证书时开启", !verifySsl) { on ->
                scope.launch { cfg.setChatVerifySsl(!on) }
            }
        }

        Section("语音 API（豆包 TTS）") {
            val vStore = remember { com.dshpet.android.voice.core.VoiceSettingsStore(ctx) }
            var vKey by remember { mutableStateOf(vStore.load().doubaoKey) }
            var vRid by remember { mutableStateOf(vStore.load().resourceId) }
            var vVoice by remember { mutableStateOf(vStore.load().voiceId) }
            Text(
                "语音合成单独配置；语言模型与上面共用一套，语音对话和文字对话都走它。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                vKey,
                {
                    vKey = it
                    val cur = vStore.load()
                    vStore.save(cur.copy(doubaoKey = it, resourceId = vRid, voiceId = vVoice))
                    scope.launch { cfg.setDoubaoApiKey(it) }
                },
                label = { Text("豆包 API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            var ridMenu by remember { mutableStateOf(false) }
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                OutlinedButton(onClick = { ridMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Resource-Id：" + vRid)
                }
                androidx.compose.material3.DropdownMenu(expanded = ridMenu, onDismissRequest = { ridMenu = false }) {
                    listOf("seed-tts-2.0", "seed-tts-1.0", "seed-icl-2.0").forEach { opt ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(opt) },
                            onClick = {
                                ridMenu = false
                                vRid = opt
                                val cur = vStore.load()
                                vStore.save(cur.copy(resourceId = opt))
                                // 必须同时写 PetConfig：VoiceRuntime.buildSettings() 以 PetConfig 为准，
                                // 只写 VoiceSettings 会被它覆盖掉（Resource-Id 改不动的根因）
                                scope.launch {
                                    cfg.setDoubaoResourceId(opt)
                                    restartVoiceIfRunning()
                                }
                            },
                        )
                    }
                }
            }
            var voiceMenu by remember { mutableStateOf(false) }
            // 音色表：唯一来源 [com.dshpet.android.voice.VoiceCatalog]（这里以前是第三份拷贝）
            val voiceOptions = com.dshpet.android.voice.VoiceCatalog.VOICES
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                OutlinedButton(onClick = { voiceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("音色：" + (voiceOptions.firstOrNull { it.first == vVoice }?.second ?: vVoice))
                }
                androidx.compose.material3.DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                    voiceOptions.forEach { (id, label) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                voiceMenu = false
                                vVoice = id
                                val cur = vStore.load()
                                vStore.save(cur.copy(voiceId = id))
                                // 同上：音色的权威源是 PetConfig，否则下一次 buildSettings 又把默认音色盖回来
                                scope.launch {
                                    cfg.setDoubaoVoiceId(id)
                                    com.dshpet.android.util.AppLog.u("语音", "音色已写入 PetConfig: $id")
                                    restartVoiceIfRunning()
                                }
                            },
                        )
                    }
                }
            }
        }
        Section("工具") {
            // 每个 API 一行：「测试」+ 可选附加能力（由 ApiTargets 工厂给出，界面不硬编码）
            var apiTargets by remember { mutableStateOf<List<com.dshpet.android.api.ApiTarget>>(emptyList()) }
            var apiResult by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            LaunchedEffect(Unit) { apiTargets = com.dshpet.android.api.ApiTargets.build(cfg) }
            apiTargets.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontSize = 13.sp)
                        Text(t.keyHint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        apiResult[t.id]?.let {
                            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // 附加能力（如「余额」）跟「测试」同一行：查的是哪个 API 一目了然
                        t.extra?.let { extra ->
                            OutlinedButton(onClick = {
                                apiResult = apiResult + (t.id to "查询中…")
                                scope.launch {
                                    val r = runCatching { extra() }.getOrElse { Result.failure(it) }
                                    apiResult = apiResult + (t.id to r.fold({ "✓ $it" }, { "✗ ${it.message}" }))
                                }
                            }) { Text(t.extraLabel ?: "查询", fontSize = 12.sp) }
                        }
                        OutlinedButton(onClick = {
                            apiResult = apiResult + (t.id to "测试中…")
                            scope.launch {
                                val r = t.test()
                                apiResult = apiResult + (t.id to r.fold({ "✓ $it" }, { "✗ ${it.message}" }))
                            }
                        }) { Text("测试", fontSize = 12.sp) }
                    }
                }
            }
            // 旧的独立「查询余额 / 测试连接」按钮已全部删除：
            // 前者并进了 DeepSeek 那一行的「余额」，后者由每行自带的「测试」取代
            SettingRow("打开 AI 对话", "与欧鲸鲸聊天（需先配置 API Key）", trailing = {
                TextButton(onClick = { ctx.startActivity(Intent(ctx, com.dshpet.android.chat.ChatActivity::class.java)) }) { Text("打开") }
            })
        }
    }
}

// ================================================================ 快捷启动
@Composable
private fun QuickLaunchTab(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { apps = cfg.quickLaunch() }
    // 已安装应用列表：**必须异步**。
    // 原实现放在 remember{} 里，会在组合（主线程）中同步执行 queryIntentActivities + 逐个 loadLabel，
    // 100+ 个应用要几百毫秒甚至更久 → 进这个页面就掉帧。
    var launchable by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) {
        launchable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val pm = ctx.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName to (it.loadLabel(pm).toString()) }
                    .distinctBy { it.first }
                    .sortedBy { it.second }
            }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("快捷启动应用") {
            apps.forEach { (pkg, label) ->
                SettingRow(label, subtitle = pkg, trailing = {
                    IconButton(onClick = {
                        scope.launch {
                            cfg.setQuickLaunch(apps.filter { it.first != pkg }.map { "${it.first}|${it.second}" }.toSet())
                        }
                        pickerOpen = false
                    }) { Icon(Icons.Filled.Delete, "移除", tint = MaterialTheme.colorScheme.error) }
                })
            }
            if (apps.isEmpty()) {
                Text("（尚未添加，长按桌宠菜单 → 快捷启动 可使用）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.Search, null, Modifier.width(16.dp))
                Text(" 添加应用")
            }
        }
    }
    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("选择要启动的应用") },
            text = {
                Column(Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
                    if (launchable == null) {
                        Text("正在扫描已安装应用…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                    }
                    launchable.orEmpty().forEach { (pkg, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val updated = (apps + (pkg to label)).distinctBy { it.first }
                                apps = updated
                                scope.launch { cfg.setQuickLaunch(updated.map { "${it.first}|${it.second}" }.toSet()) }
                                pickerOpen = false
                            }.padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text("关闭") } },
        )
    }
}

// ================================================================ 关于
@Composable
private fun AboutTab(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    var updateInfo by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("版本") {
            SettingRow("dsh-pet 桌宠（Android 移植版）", subtitle = "v${BuildConfig.VERSION_NAME} · 基于 dsh-pet-indesktop v4.0.1 移植")
            SettingRow("查看日志", subtitle = "崩溃排查：浏览/复制/分享内置日志（按日期存储）", trailing = {
                TextButton(onClick = {
                    ctx.startActivity(Intent(ctx, com.dshpet.android.LogActivity::class.java))
                }) { Text("打开") }
            })
            SettingRow("检查更新", subtitle = updateInfo, trailing = {
                TextButton(onClick = {
                    updateInfo = "检查中…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Updater.latestRelease() }
                        updateInfo = r.fold({ rel ->
                            if (Updater.isNewer(rel.tag, BuildConfig.VERSION_NAME)) "发现新版本 ${rel.tag}！点击右侧打开下载页"
                            else "已经是最新版本"
                        }, { "检查失败：${it.message}" })
                        r.getOrNull()?.let { _ ->
                            // 打开下载页
                        }
                    }
                }) { Text("检查") }
            }, onClick = { updateInfo?.let { if (it.contains("下载页")) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_URL))) } } })
        }
        Section("链接") {
            SettingRow("GitHub 仓库与 Releases", subtitle = Updater.RELEASES_URL, onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_URL))) }
            })
            SettingRow("桌面端原项目", subtitle = "github.com/MerZlin/dsh-pet-indesktop", onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MerZlin/dsh-pet-indesktop"))) }
            })
        }
        Section("说明") {
            Text(
                "本应用为 dsh-pet 桌面桌宠的 Android 移植版：透明悬浮窗显示在任意应用之上；" +
                        "素材在构建时由 WebM 重编码为旁路 alpha 视频，手机上无需任何额外组件。\n\n" +
                        "许可：MIT（见桌面端仓库 LICENSE）。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/**
 * 「测试」标签页（最右一栏）：所有**调试 / 试音 / 动画验证**的入口集中在这里。
 *
 * 之所以集中：这些入口原来散在「语音与记忆设置」页里，和普通用户的配置混在一起；
 * 搬出来以后，正式设置页只留用户能理解的东西。
 */
@Composable
private fun TestTab(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val border by cfg.flowBool("window_border", false).collectAsState(initial = false)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section("桌宠") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching { com.dshpet.android.pet.PetOverlayService.ensureRunning(ctx, 0, persist = true) }
                            .onSuccess {
                                android.widget.Toast.makeText(ctx, "已请求启动，回到桌面看", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .onFailure {
                                android.widget.Toast.makeText(
                                    ctx, "启动失败：" + (it.message ?: it.javaClass.simpleName),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                    },
                    modifier = Modifier.weight(1f).height(42.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) {
                    Text("强制启动桌宠", fontSize = 13.sp, maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                OutlinedButton(
                    onClick = { ctx.startActivity(Intent(ctx, com.dshpet.android.pet.PetAnimTestActivity::class.java)) },
                    modifier = Modifier.weight(1f).height(42.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) {
                    Text("动画测试", fontSize = 13.sp, maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            // 窗口边框改成开关行：原来是挤在按钮排里的第三个按钮，
            // 窄屏上被压得竖排、字也不居中（用户反馈"太丑"）
            SwitchRow("窗口边框", "画出悬浮窗真实矩形（含透明边距），排查「盖住/点不到」用", border) { on ->
                scope.launch { cfg.setWindowBorder(on) }
            }
            Text(
                "「强制启动桌宠」绕过开关直接拉起悬浮窗服务；" +
                        "「动画测试」列出全部 91 段动画，点一条就让屏幕上的桌宠播那一段（可核对尺寸/镜像/落点）。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Section("语音") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(ctx, com.dshpet.android.voice.VoiceLoopActivity::class.java))
                }) { Text("语音对话测试台", fontSize = 13.sp) }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(ctx, com.dshpet.android.voice.VoiceTestActivity::class.java))
                }) { Text("TTS 试音台", fontSize = 13.sp) }
            }
            Text(
                "语音对话测试台：听写、打字、记忆整理、前摄观察。TTS 试音台：换音色、调语速音量、试参数。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Section("排障提示") {
            Text(
                "• 桌宠挡住按钮 / 点不到 → 外观 → 「显示窗口边框」，画出悬浮窗真实矩形（含透明边距）\n" +
                        "• 找不到桌宠 → 测试 → 「强制启动桌宠」\n" +
                        "• 语音没声音 → 测试 → 「TTS 试音台」先单独验证音色与 Key\n" +
                        "• 动画不对 / 被裁 → 测试 → 「动画测试」逐条看\n" +
                        "• 崩溃与启动记录 → 语音设置 → 「用户日志」",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
