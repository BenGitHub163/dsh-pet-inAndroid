package com.dshpet.android.data

import com.dshpet.android.api.DeepseekApi

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// 全局设置 DataStore：手动工厂单例（损坏时删除重建，不炸 CorruptionException）。
// appContext 由 PetApp 启动时注入（Application 上下文，进程级单例安全）。
private val globalStore: DataStore<Preferences> by lazy {
    val appCtx = com.dshpet.android.PetApp.injectedAppContext
        ?: throw IllegalStateException("PetApp 未初始化（正常不应发生）")
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
    ) {
        appCtx.preferencesDataStoreFile("dshpet")
    }
}
private val Context.dataStore: DataStore<Preferences> get() = globalStore

/**
 * 全局设置存储（DataStore）。对应原桌面端 config.json 的字段子集 +
 * Android 特有项（毛玻璃、隐藏后台、忽略电池优化等）。
 * 多开实例的"位置/朝向"单独存（见 [PetState]），全局设置各实例共享。
 */
class PetConfig(private val ctx: Context) {

    private val ds get() = ctx.dataStore

    companion object {

        // ---- 内存快照：界面/请求线程零阻塞读取，避免主线程 runBlocking DataStore ----
        @Volatile var snapReady = false
        @Volatile var snapBaseUrl = ""
    @Volatile var snapChatPath = ""
        @Volatile var snapModel = ""
        @Volatile var snapTemperature = 1.0
        @Volatile var snapMaxTokens = 2048
        @Volatile var snapTimeout = 60
        @Volatile var snapVerifySsl = true
        @Volatile var snapDoubaoKey = ""
        @Volatile var snapDoubaoRid = "seed-tts-2.0"
        @Volatile var snapDoubaoVoice = ""
        fun get(ctx: Context) = PetConfig(ctx)

    /** 后台调用：把常用配置一次性读进内存快照 */
    suspend fun refreshSnapshot(ctx: Context) {
        val c = PetConfig(ctx)
        runCatching {
            KeyFactory.reload(c, com.dshpet.android.voice.core.VoiceSettingsStore.get(ctx).load())
        }
        val keys = KeyFactory.current()
        snapBaseUrl = c.chatBaseUrl()
        snapChatPath = c.chatPath()
        snapModel = c.chatModel()
        snapTemperature = c.chatTemperature()
        snapMaxTokens = c.chatMaxTokens()
        snapTimeout = c.chatTimeout()
        snapVerifySsl = c.chatVerifySsl()
        snapDoubaoKey = keys.doubao
        snapDoubaoRid = c.doubaoResourceId()
        snapDoubaoVoice = c.doubaoVoiceId()
        snapReady = true
    }

        /**
         * "隐藏后台"进程级缓存：通知/悬浮窗菜单等非挂起上下文里
         * 启动 Activity 时需要同步读取（DataStore 是 suspend 的）。
         * PetApp 启动时与设置切换时同步。
         */
        @Volatile
        var hideRecentsCached = false

        // ---- 缩放档位（相对 640 宽，与原 SCALE_STEPS 一致）----
        val SCALE_STEPS = listOf(0.5, 0.72, 0.85, 1.0)

        /** 默认播放速度（手机上 1x 偏慢，默认 1.5x） */
        const val DEFAULT_PLAYBACK_SPEED = 1.5

        /** 自言自语最多几条（编辑页固定给这么多格，够用又不至于翻太久） */
        const val MAX_SELF_TALK_LINES = 15

        private val K_CHARACTER = stringPreferencesKey("character")
        private val K_SCALE = doublePreferencesKey("scale")
        private val K_FACING = stringPreferencesKey("facing")
        private val K_ON_TOP = booleanPreferencesKey("on_top")
        private val K_NO_MOVE = booleanPreferencesKey("no_move")
        private val K_LOCK_POSITION = booleanPreferencesKey("lock_position")
        private val K_SHIFT_DRAG = booleanPreferencesKey("shift_drag")       // Android: 仅长按可拖动
        private val K_DRAG_PHYSICS = booleanPreferencesKey("drag_physics")
        /** 自言自语台词（一行一条，'\n' 分隔，最多 [MAX_SELF_TALK_LINES] 条；空串 = 用旧集合/内置默认） */
        private val K_SELF_TALK_LINES = stringPreferencesKey("self_talk_lines")
        /** 重力感应：用加速度计决定"下"在哪，桌宠往那个方向掉 */
        private val K_GRAVITY_SENSOR = booleanPreferencesKey("gravity_sensor")
        private val K_PET_OPACITY = intPreferencesKey("pet_opacity")
        private val K_WINDOW_BORDER = booleanPreferencesKey("window_border")   // 调试：描出悬浮窗矩形
        private val K_PLAYBACK_SPEED = doublePreferencesKey("playback_speed")
        private val K_MOVE_PROBABILITY = doublePreferencesKey("move_probability")   // 移动概率 0..1
        private val K_MOVE_MIN_PX = intPreferencesKey("move_min_px")                // 散步最小距离
        private val K_MOVE_MAX_PX = intPreferencesKey("move_max_px")                // 散步最大距离
        private val K_MENU_SCALE = doublePreferencesKey("menu_scale")               // 菜单缩放
        private val K_MAX_INSTANCES = intPreferencesKey("max_instances")            // 多开上限(0=不限)
        private val K_AGENT_LINK = booleanPreferencesKey("agent_link_enabled")      // Agent 联动插件总线
        private val K_COLLISION = booleanPreferencesKey("pet_collision")             // 多开碰撞物理
        private val K_ISLAND_ENABLED = booleanPreferencesKey("island_enabled")       // 灵动岛
        private val K_ISLAND_STYLE = stringPreferencesKey("island_style")            // dark/light/glass
        private val K_ISLAND_EMOJI = stringPreferencesKey("island_emoji")            // 图标
        private val K_ISLAND_TEXT = stringPreferencesKey("island_text")              // 自定义文本
        private val K_ISLAND_X = intPreferencesKey("island_x")                       // 位置记忆
        private val K_ISLAND_Y = intPreferencesKey("island_y")
        private val K_ISLAND_W_PCT = intPreferencesKey("island_width_pct")  // 宽（屏宽%）
        private val K_ISLAND_H_DP = intPreferencesKey("island_height_dp")   // 高（dp）
        private val K_THROW_STRENGTH = stringPreferencesKey("throw_strength")  // 甩出力度档位
        private val K_SOUND_VOLUME = intPreferencesKey("sound_volume")        // 音效音量
        private val K_CLICK_TALK = stringPreferencesKey("click_talk")          // 点击台词绑定
        private val K_MODEL_LIST = stringSetPreferencesKey("model_list")         // 已同步模型列表
        private val K_AGENT_TREE_URI = stringPreferencesKey("agent_events_tree")  // SAF 事件目录
        private val K_ANIM_GAP_SEC = doublePreferencesKey("animation_gap_seconds")
        private val K_CLICK_SOUND = booleanPreferencesKey("click_sound_enabled")
        private val K_CLICK_SHOW_BALANCE = booleanPreferencesKey("click_show_balance")
        private val K_CLICK_SHOW_SELF_TALK = booleanPreferencesKey("click_show_self_talk")

        // ---- 自言自语 ----
        private val K_SELF_TALK = booleanPreferencesKey("self_talk_enabled")
        private val K_SELF_TALK_MIN = intPreferencesKey("self_talk_min_interval")
        private val K_SELF_TALK_MAX = intPreferencesKey("self_talk_max_interval")
        private val K_SELF_TALK_DURATION = doublePreferencesKey("self_talk_duration_seconds")
        private val K_SELF_TALK_TEXTS = stringSetPreferencesKey("self_talk_texts")
        private val K_SELF_TALK_BUBBLE_STYLE = stringPreferencesKey("self_talk_bubble_style")
        /** 自定义气泡底色（"#AARRGGBB"；空串 = 跟随「气泡样式」自带颜色） */
        private val K_SELF_TALK_BUBBLE_COLOR = stringPreferencesKey("self_talk_bubble_color")
        /** 台词改由 DeepSeek 现场生成（没 Key 自动回退固定台词） */
        private val K_SELF_TALK_AI = booleanPreferencesKey("self_talk_ai")
        /** 生成后顺便用豆包语音念出来（需要豆包 Key） */
        private val K_SELF_TALK_AI_VOICE = booleanPreferencesKey("self_talk_ai_voice")

        // ---- 通用 Android 项 ----
        private val K_BLUR = booleanPreferencesKey("blur_enabled")            // 毛玻璃（默认关）
        private val K_HIDE_RECENTS = booleanPreferencesKey("hide_from_recents") // 隐藏后台
        private val K_AUTO_START = booleanPreferencesKey("autostart")          // 开机自启
        private val K_BATTERY_OPT = booleanPreferencesKey("battery_optimization")
        private val K_OVERLAY_PERM = booleanPreferencesKey("overlay_permission_granted")
        private val K_PET_RUNNING = booleanPreferencesKey("pet_running")
        private val K_MOUSE_THROUGH = booleanPreferencesKey("mouse_through") // 桌面端兼容保留（Android 无意义）

        // ---- AI 对话 ----
        private val K_CHAT_NAME = stringPreferencesKey("chat_provider_name")
        private val K_CHAT_BASE_URL = stringPreferencesKey("chat_base_url")
        private val K_CHAT_PATH = stringPreferencesKey("chat_chat_path")
        private val K_CHAT_MODEL = stringPreferencesKey("chat_model")
        private val K_CHAT_API_KEY = stringPreferencesKey("chat_api_key")
        private val K_DOUBAO_API_KEY = stringPreferencesKey("doubao_api_key")
        private val K_DOUBAO_RESOURCE_ID = stringPreferencesKey("doubao_resource_id")
        private val K_DOUBAO_VOICE_ID = stringPreferencesKey("doubao_voice_id")
        private val K_BREEZE_API_KEY = stringPreferencesKey("breeze_api_key")
        private val K_CHAT_TEMPERATURE = doublePreferencesKey("chat_temperature")
        private val K_CHAT_MAX_TOKENS = intPreferencesKey("chat_max_tokens")
        private val K_CHAT_TIMEOUT = intPreferencesKey("chat_timeout")
        private val K_CHAT_VERIFY_SSL = booleanPreferencesKey("chat_verify_ssl")
        private val K_BALANCE_REFRESH_MIN = intPreferencesKey("balance_refresh_minutes")

        // ---- 快捷启动 ----
        private val K_QUICK_LAUNCH = stringSetPreferencesKey("quick_launch_apps") // "pkg|label"

        // ---- 多开 ----
        private val K_INSTANCE_COUNT = intPreferencesKey("spawned_instance_count")

        const val DEFAULT_CHARACTER = "shenshen"
        const val DEFAULT_SELF_TALK_TEXT =
            "好女孩……好模型……欧鲸鲸……今天也要认真工作呀。再陪你一会儿。"

        /**
         * 内置默认台词（**唯一来源**）。
         * 存成空集合 / 全是空行时兜底，见 [selfTalkTexts]；
         * 悬浮窗服务里的 `self_talk_texts` watcher 也要用它兜底（否则第一次回调就把默认值覆盖成空）。
         */
        fun defaultSelfTalkTexts(): Set<String> =
            setOf("好女孩……", "好模型……", "欧鲸鲸……", "今天也要认真工作呀。", "再陪你一会儿。")
    }

    // ================ 读取 ================
    private suspend fun <T> read(key: androidx.datastore.preferences.core.Preferences.Key<T>, default: T): T =
        ds.data.first()[key] ?: default

    suspend fun character() = read(K_CHARACTER, DEFAULT_CHARACTER)
    suspend fun scale() = read(K_SCALE, 0.72)
    suspend fun facing() = read(K_FACING, "left")
    suspend fun noMove() = read(K_NO_MOVE, false)
    suspend fun shiftDrag() = read(K_SHIFT_DRAG, false)
    /** 重力感应（加速度计）开关 */
    suspend fun gravitySensor() = read(K_GRAVITY_SENSOR, false)

    /**
     * 自言自语台词（有序，最多 [MAX_SELF_TALK_LINES] 条）。
     *
     * 新存储是 `self_talk_lines`（一行一条，**保序**，配合"逐个保存/逐个删除"的编辑页）；
     * 老版本只有 `self_talk_texts` 集合，所以这里做一次兜底迁移：
     * 新键为空 → 读旧集合（旧集合为空还会再退到内置默认 5 条）。
     */
    suspend fun selfTalkLines(): List<String> {
        val raw = read(K_SELF_TALK_LINES, "")
        if (raw.isNotBlank()) {
            return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_SELF_TALK_LINES)
        }
        return selfTalkTexts().toList().take(MAX_SELF_TALK_LINES)
    }

    /** 写台词：同时更新有序的 `self_talk_lines` 和旧的 `self_talk_texts`（服务侧监听的是后者） */
    suspend fun setSelfTalkLines(lines: List<String>) {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_SELF_TALK_LINES)
        set("self_talk_lines", clean.joinToString("\n"))
        set("self_talk_texts", clean.toSet())
        refreshSnap()
    }
    suspend fun windowBorder() = read(K_WINDOW_BORDER, false)
    suspend fun playbackSpeed() = read(K_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED).coerceIn(0.5, 3.0)
    suspend fun clickSound() = read(K_CLICK_SOUND, true)

    suspend fun selfTalkEnabled() = read(K_SELF_TALK, false)
    suspend fun selfTalkMin() = read(K_SELF_TALK_MIN, 20)
    suspend fun selfTalkMax() = read(K_SELF_TALK_MAX, 60)
    suspend fun selfTalkDuration() = read(K_SELF_TALK_DURATION, 3.2)
    /**
     * 自言自语台词。
     *
     * **空集合要退回内置默认**：旧版本存过空集合，而界面上又没有编辑入口，
     * 于是"允许自言自语"开着、定时器也在跑，却永远什么都不显示
     * （日志里就是 `SELFTALK 素材为空，跳过`）。这也是"自说自话气泡从未出现"的直接原因之一。
     */
    suspend fun selfTalkTexts(): Set<String> =
        read(K_SELF_TALK_TEXTS, defaultSelfTalkTexts()).ifEmpty { defaultSelfTalkTexts() }
    /** 台词是否交给 DeepSeek 现场生成 */
    suspend fun selfTalkAi() = read(K_SELF_TALK_AI, false)
    /** 生成后是否再用语音念出来 */
    suspend fun selfTalkAiVoice() = read(K_SELF_TALK_AI_VOICE, false)
    suspend fun selfTalkBubbleStyle() = read(K_SELF_TALK_BUBBLE_STYLE, "classic_top")
    /** 自定义气泡底色（"#AARRGGBB"；空串 = 跟随样式） */
    suspend fun selfTalkBubbleColor() = read(K_SELF_TALK_BUBBLE_COLOR, "")

    suspend fun blurEnabled() = read(K_BLUR, false)
    suspend fun hideFromRecents() = read(K_HIDE_RECENTS, true)
    suspend fun autoStart() = read(K_AUTO_START, false)
    suspend fun batteryOpt() = read(K_BATTERY_OPT, false)
    suspend fun overlayPermissionGranted() = read(K_OVERLAY_PERM, false)
    suspend fun petRunning() = read(K_PET_RUNNING, false)

    suspend fun chatBaseUrl() = read(K_CHAT_BASE_URL, DeepseekApi.CHAT_BASE_URL)
    suspend fun chatPath() = read(K_CHAT_PATH, DeepseekApi.CHAT_PATH)
    suspend fun chatModel() = read(K_CHAT_MODEL, DeepseekApi.CHAT_MODEL)
    /** 写入后立即刷新内存快照（不用等下次启动） */
    suspend fun refreshSnap() {
        runCatching {
            KeyFactory.reload(this)
            val keys = KeyFactory.current()
            snapBaseUrl = chatBaseUrl()
            snapChatPath = chatPath()
            snapModel = chatModel()
            snapTemperature = chatTemperature()
            snapMaxTokens = chatMaxTokens()
            snapTimeout = chatTimeout()
            snapVerifySsl = chatVerifySsl()
            snapDoubaoKey = keys.doubao
            snapDoubaoRid = doubaoResourceId()
            snapDoubaoVoice = doubaoVoiceId()
            snapReady = true
        }
    }

    // ---- Key：数据库（DataStore）→ 构建期配置（keys.properties）→ 空串，见 [KeyFactory] ----

    /** 只读数据库里的对话 key（不回退），给 [KeyFactory] 用 */
    suspend fun chatApiKeyRaw() = read(K_CHAT_API_KEY, "")

    /** 只读数据库里的豆包 key（不回退），给 [KeyFactory] 用 */
    suspend fun doubaoApiKeyRaw() = read(K_DOUBAO_API_KEY, "")

    /** 只读数据库里的 Breeze key（不回退），给 [KeyFactory] 用 */
    suspend fun breezeApiKeyRaw() = read(K_BREEZE_API_KEY, "")

    /** 对话 key：走 [KeyFactory] 快照（保存后会 reload）。 */
    suspend fun chatApiKey() = KeyFactory.current().deepseek

    /** 豆包语音 key：走 [KeyFactory] 快照。 */
    suspend fun doubaoApiKey() = KeyFactory.current().doubao
    // 音色 / 资源 id 的默认值必须留空：语音层的权威来源是这里的 DataStore，
    // "没设置过"时要能回退到 VoiceSettings 里的用户选择，否则默认值会盖掉用户选的音色。
    suspend fun doubaoResourceId() = read(K_DOUBAO_RESOURCE_ID, "")
    suspend fun doubaoVoiceId() = read(K_DOUBAO_VOICE_ID, "")
    suspend fun chatTemperature() = read(K_CHAT_TEMPERATURE, 0.7).coerceIn(0.0, 2.0)
    suspend fun chatMaxTokens() = read(K_CHAT_MAX_TOKENS, 2048)
    suspend fun chatTimeout() = read(K_CHAT_TIMEOUT, 60)
    suspend fun chatVerifySsl() = read(K_CHAT_VERIFY_SSL, true)

    suspend fun quickLaunch(): List<Pair<String, String>> =
        read(K_QUICK_LAUNCH, emptySet())
            .mapNotNull { it.split("|", limit = 2).let { p -> if (p.size == 2) p[0] to p[1] else null } }


    // ================ 写入 ================
    suspend fun set(key: String, value: Any) {
        ds.edit { prefs ->
            when (value) {
                is Boolean -> prefs[booleanPreferencesKey(key)] = value
                is Int -> prefs[intPreferencesKey(key)] = value
                is Long -> prefs[longPreferencesKey(key)] = value
                is Float -> prefs[floatPreferencesKey(key)] = value
                is Double -> prefs[doublePreferencesKey(key)] = value
                is String -> prefs[stringPreferencesKey(key)] = value
                is Set<*> -> prefs[stringSetPreferencesKey(key)] = value.filterIsInstance<String>().toSet()
            }
        }
    }

    suspend fun setScale(v: Double) = set("scale", v).also { refreshSnap() }
    suspend fun setFacing(v: String) = set("facing", v).also { refreshSnap() }
    suspend fun setNoMove(v: Boolean) = set("no_move", v).also { refreshSnap() }
    suspend fun setLockPosition(v: Boolean) = set("lock_position", v).also { refreshSnap() }
    suspend fun setShiftDrag(v: Boolean) = set("shift_drag", v).also { refreshSnap() }
    suspend fun setDragPhysics(v: Boolean) = set("drag_physics", v).also { refreshSnap() }
    suspend fun setGravitySensor(v: Boolean) = set("gravity_sensor", v).also { refreshSnap() }
    suspend fun setPetOpacity(v: Int) = set("pet_opacity", v).also { refreshSnap() }
    suspend fun setWindowBorder(v: Boolean) = set("window_border", v).also { refreshSnap() }
    suspend fun setPlaybackSpeed(v: Double) = set("playback_speed", v).also { refreshSnap() }
    suspend fun moveProbability() = read(K_MOVE_PROBABILITY, 0.20).coerceIn(0.0, 0.9)
    suspend fun setMoveProbability(v: Double) { set("move_probability", v.coerceIn(0.0, 0.9)) }
    suspend fun moveMinPx() = read(K_MOVE_MIN_PX, 60).coerceIn(10, 600)
    suspend fun moveMaxPx() = read(K_MOVE_MAX_PX, 240).coerceIn(30, 1200)
    suspend fun setMoveRange(minPx: Int, maxPx: Int) {
        val lo = minPx.coerceIn(10, 600)
        val hi = maxPx.coerceIn(lo, 1200)
        set("move_min_px", lo); set("move_max_px", hi)
    }
    suspend fun menuScale() = read(K_MENU_SCALE, 1.0).coerceIn(0.7, 1.4)
    suspend fun setMenuScale(v: Double) = set("menu_scale", v.coerceIn(0.7, 1.4))
    suspend fun maxInstances() = read(K_MAX_INSTANCES, 4).coerceIn(0, 10)
    suspend fun setMaxInstances(v: Int) = set("max_instances", v.coerceIn(0, 10))
    suspend fun agentLinkEnabled() = read(K_AGENT_LINK, false)
    suspend fun setAgentLinkEnabled(v: Boolean) = set("agent_link_enabled", v).also { refreshSnap() }
    suspend fun collisionEnabled() = read(K_COLLISION, true)
    suspend fun setCollisionEnabled(v: Boolean) = set("pet_collision", v).also { refreshSnap() }
    suspend fun islandEnabled() = read(K_ISLAND_ENABLED, false)
    suspend fun setIslandEnabled(v: Boolean) = set("island_enabled", v).also { refreshSnap() }
    suspend fun islandStyle() = read(K_ISLAND_STYLE, "dark")
    suspend fun setIslandStyle(v: String) = set("island_style", v).also { refreshSnap() }
    suspend fun islandEmoji() = read(K_ISLAND_EMOJI, "🐳")
    suspend fun setIslandEmoji(v: String) = set("island_emoji", v).also { refreshSnap() }
    suspend fun islandText() = read(K_ISLAND_TEXT, "")
    suspend fun setIslandText(v: String) = set("island_text", v).also { refreshSnap() }
    suspend fun islandX() = read(K_ISLAND_X, -1)
    suspend fun islandY() = read(K_ISLAND_Y, -1)
    suspend fun setIslandPos(x: Int, y: Int) { set("island_x", x); set("island_y", y) }
    suspend fun islandWidthPct() = read(K_ISLAND_W_PCT, 50).coerceIn(30, 90)
    suspend fun setIslandWidthPct(v: Int) = set("island_width_pct", v.coerceIn(30, 90))
    suspend fun islandHeightDp() = read(K_ISLAND_H_DP, 54).coerceIn(36, 96)
    suspend fun setIslandHeightDp(v: Int) = set("island_height_dp", v.coerceIn(36, 96))
    suspend fun throwStrength() = read(K_THROW_STRENGTH, "standard")
    suspend fun setThrowStrength(v: String) = set("throw_strength", v).also { refreshSnap() }
    suspend fun soundVolume() = read(K_SOUND_VOLUME, 100).coerceIn(0, 100)
    suspend fun setSoundVolume(v: Int) = set("sound_volume", v.coerceIn(0, 100))
    suspend fun clickTalk() = read(K_CLICK_TALK, "")
    suspend fun setClickTalk(v: String) = set("click_talk", v.take(60))
    suspend fun setModelList(list: List<String>) = set("model_list", list.toSet())
    suspend fun agentEventsTreeUri() = read(K_AGENT_TREE_URI, "")
    suspend fun setAgentEventsTreeUri(v: String) = set("agent_events_tree", v).also { refreshSnap() }
    suspend fun setAnimGap(v: Double) = set("animation_gap_seconds", v).also { refreshSnap() }
    suspend fun setClickSound(v: Boolean) = set("click_sound_enabled", v).also { refreshSnap() }
    suspend fun setClickShowBalance(v: Boolean) = set("click_show_balance", v).also { refreshSnap() }
    suspend fun setClickShowSelfTalk(v: Boolean) = set("click_show_self_talk", v).also { refreshSnap() }
    suspend fun setSelfTalk(v: Boolean) = set("self_talk_enabled", v).also { refreshSnap() }
    suspend fun setSelfTalkMin(v: Int) = set("self_talk_min_interval", v).also { refreshSnap() }
    suspend fun setSelfTalkMax(v: Int) = set("self_talk_max_interval", v).also { refreshSnap() }
    suspend fun setSelfTalkDuration(v: Double) = set("self_talk_duration_seconds", v).also { refreshSnap() }
    suspend fun setSelfTalkAi(v: Boolean) = set("self_talk_ai", v).also { refreshSnap() }
    suspend fun setSelfTalkAiVoice(v: Boolean) = set("self_talk_ai_voice", v).also { refreshSnap() }
    suspend fun setSelfTalkBubbleStyle(v: String) = set("self_talk_bubble_style", v).also { refreshSnap() }
    suspend fun setSelfTalkBubbleColor(v: String) = set("self_talk_bubble_color", v).also { refreshSnap() }
    suspend fun setBlur(v: Boolean) = set("blur_enabled", v).also { refreshSnap() }
    suspend fun setHideFromRecents(v: Boolean) = set("hide_from_recents", v).also { refreshSnap() }
    suspend fun setAutoStart(v: Boolean) = set("autostart", v).also { refreshSnap() }
    suspend fun setBatteryOpt(v: Boolean) = set("battery_optimization", v).also { refreshSnap() }
    suspend fun setOverlayPermission(v: Boolean) = set("overlay_permission_granted", v).also { refreshSnap() }
    suspend fun setPetRunning(v: Boolean) = set("pet_running", v).also { refreshSnap() }

    suspend fun setChatProviderName(v: String) = set("chat_provider_name", v).also { refreshSnap() }
    suspend fun setChatBaseUrl(v: String) = set("chat_base_url", v).also { refreshSnap() }
    suspend fun setChatPath(v: String) = set("chat_chat_path", v).also { refreshSnap() }
    suspend fun setChatModel(v: String) = set("chat_model", v).also { refreshSnap() }
    suspend fun setChatApiKey(v: String) = set("chat_api_key", v).also { refreshSnap() }
    suspend fun setDoubaoApiKey(v: String) = set("doubao_api_key", v).also { refreshSnap() }
    suspend fun setBreezeApiKey(v: String) = set("breeze_api_key", v).also { refreshSnap() }
    suspend fun setDoubaoResourceId(v: String) = set("doubao_resource_id", v).also { refreshSnap() }
    suspend fun setDoubaoVoiceId(v: String) = set("doubao_voice_id", v).also { refreshSnap() }
    suspend fun setChatTemperature(v: Double) = set("chat_temperature", v).also { refreshSnap() }
    suspend fun setChatMaxTokens(v: Int) = set("chat_max_tokens", v).also { refreshSnap() }
    suspend fun setChatVerifySsl(v: Boolean) = set("chat_verify_ssl", v).also { refreshSnap() }
    suspend fun setQuickLaunch(v: Set<String>) = set("quick_launch_apps", v)


    // ================ Flow 版本（供 Compose 响应式 UI） ================
    // 注意：DataStore 任意键的每次 edit 都会重发整个 Preferences；
    // 必须 distinctUntilChanged，否则改任何设置会让所有 watch 重复触发
    //（灵动岛曾因此被反复重建）
    fun <T> flow(key: androidx.datastore.preferences.core.Preferences.Key<T>, default: T): Flow<T> =
        ds.data.map { it[key] ?: default }.distinctUntilChanged()

    fun flowBool(key: String, default: Boolean) =
        flow(booleanPreferencesKey(key), default)

    fun flowDouble(key: String, default: Double) =
        flow(doublePreferencesKey(key), default)

    fun flowInt(key: String, default: Int) =
        flow(intPreferencesKey(key), default)

    fun flowString(key: String, default: String) =
        flow(stringPreferencesKey(key), default)

    fun flowStringSet(key: String, default: Set<String>) =
        flow(stringSetPreferencesKey(key), default)

    // ================ 同步读取（协程内） ================
    suspend fun getString(key: String, default: String) =
        ds.data.first()[stringPreferencesKey(key)] ?: default

    suspend fun getBool(key: String, default: Boolean) =
        ds.data.first()[booleanPreferencesKey(key)] ?: default

    suspend fun getInt(key: String, default: Int) =
        ds.data.first()[intPreferencesKey(key)] ?: default

    suspend fun getDouble(key: String, default: Double) =
        ds.data.first()[doublePreferencesKey(key)] ?: default

}

/**
 * 每只桌宠实例独立的状态（位置/朝向）：多开时互不覆盖。
 * 存于独立 DataStore 文件 pet_state_<instanceId>。
 *
 * DataStore 必须进程内单例：同一文件多个 DataStore 实例会抛
 * "multiple DataStores active"（服务关闭再启动即触发），
 * 故用 companion 缓存按 instanceId 复用。
 */
class PetState(ctx: Context, instanceId: Int) {

    private val ds = storeFor(ctx.applicationContext, instanceId)

    suspend fun load(): State {
        val p = ds.data.first()
        return State(
            x = p[intPreferencesKey("x")] ?: -1,
            y = p[intPreferencesKey("y")] ?: -1,
            facing = p[stringPreferencesKey("facing")] ?: "left",
        )
    }

    suspend fun save(s: State) {
        ds.edit {
            it[intPreferencesKey("x")] = s.x
            it[intPreferencesKey("y")] = s.y
            it[stringPreferencesKey("facing")] = s.facing
        }
    }

    data class State(val x: Int = -1, val y: Int = -1, val facing: String = "left")

    companion object {
        private val stores = java.util.concurrent.ConcurrentHashMap<Int, DataStore<Preferences>>()

        private fun storeFor(ctx: Context, id: Int): DataStore<Preferences> =
            stores.getOrPut(id) {
                // 文件损坏（进程被杀时写一半）→ 删除重建，避免
                // CorruptionException 让桌宠永远起不来
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
                ) {
                    ctx.preferencesDataStoreFile("pet_state_$id")
                }
            }
    }
}


/** 隐藏后台开启时，给新任务的 Intent 加排除标志（最近任务列表不显示） */
fun android.content.Intent.applyHideRecentsPolicy(): android.content.Intent = apply {
    if (PetConfig.hideRecentsCached) {
        addFlags(android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
