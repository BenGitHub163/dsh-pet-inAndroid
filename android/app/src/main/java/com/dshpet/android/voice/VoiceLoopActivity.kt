package com.dshpet.android.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.dshpet.android.voice.core.VoiceEvent
import com.dshpet.android.voice.core.VoiceLoop
import com.dshpet.android.voice.core.VoiceSettings
import com.dshpet.android.voice.core.VoiceSettingsStore
import com.dshpet.android.voice.core.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 语音对话界面（**纯 View 层**：只渲染 VoiceState、只转发用户操作）。
 *
 * 所有业务逻辑在 [VoiceLoop] 里；这里不碰 ASR/TTS/网络，
 * 换成桌宠悬浮窗/Service 时这个 Activity 可以整体扔掉。
 */
class VoiceLoopActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var loop: VoiceLoop
    private lateinit var store: VoiceSettingsStore

    /**
     * 这个 Activity **是否拥有** [loop] 的生命周期。
     *
     * 一开始是我们自己 new 的（true）；一旦接上 `VoiceRuntime.ensureStarted()` 返回的
     * **共享单例**（桌宠悬浮窗也在用同一个），就变成 false——此时关掉这个页面绝不能
     * `release()`，否则会把桌宠正在进行的语音会话一起杀掉。
     */
    private var ownsLoop = true

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var dsView: TextView
    private lateinit var dsReplyView: TextView
    private lateinit var capsText: TextView
    private lateinit var usageHint: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var providerSpinner: Spinner
    private lateinit var breezeVoiceSpinner: Spinner
    private var breezeVoices: List<Pair<String, String>> = emptyList()
    private lateinit var cfgSummary: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var memoryBtn: Button
    private lateinit var privacyBtn: Button
    private lateinit var visionBtn: Button
    private val cameraSnap by lazy { com.dshpet.android.voice.vision.CameraSnap(this) }

    /** 上一次"语音里让桌宠看一眼"自动拍照的时间（毫秒，elapsedRealtime） */
    private var lastAutoLookMs = 0L

    companion object {
        /** 按下/说完之后等多久再拍：留时间抬头、调整姿势 */
        private const val SNAP_DELAY_MS = 300L

        /** 语音触发自动拍照的最小间隔：ASR 常把一句话切成几段，避免连拍好几次 */
        private const val AUTO_LOOK_MIN_GAP_MS = 20_000L

        /** "看我一眼"的各种说法（去空格后做包含匹配） */
        private val LOOK_PHRASES = arrayOf(
            "看我一眼", "看我一下", "看下我", "看看我", "看一眼我", "瞧瞧我", "瞅瞅我", "看我的样子",
        )
    }

    /** 这句话是不是在让桌宠"看你一眼" */
    private fun wantsLook(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.replace(" ", "")
        return LOOK_PHRASES.any { t.contains(it) }
    }
    private lateinit var textInput: EditText

    /** 音色表：唯一来源见 [com.dshpet.android.voice.VoiceCatalog]（以前这里抄了一份） */
    private val voices = com.dshpet.android.voice.VoiceCatalog.VOICES

    private var settings = VoiceSettings()
    private var logJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "语音对话（测试）"
        loop = VoiceLoop(this)
        store = VoiceSettingsStore(this)
        settings = store.load()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 40)
        }

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 12f; setPadding(0, 18, 0, 4); setTextColor(0xFF666666.toInt())
        }

        capsText = TextView(this).apply {
            textSize = 11f; setTextColor(0xFF888888.toInt()); setPadding(0, 8, 0, 0)
        }
        root.addView(capsText)

        // 前台应用感知未开启时，给一个直接跳系统设置的入口（这是特殊权限，不能用运行时弹窗申请）
        usageHint = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF1565C0.toInt())
            setPadding(0, 4, 0, 0)
            visibility = android.view.View.GONE
            setOnClickListener { loop.openUsageAccessSettings() }
        }
        root.addView(usageHint)

        root.addView(label("豆包 TTS API Key"))

        root.addView(label("DeepSeek API Key"))

        // ---------- 服务商（与「AI 对话 → 语音 API」同一份配置；这里改只影响本次测试） ----------
        root.addView(label("语音服务商（和「语音 API」页共用配置；这里改只影响本次测试）"))
        providerSpinner = Spinner(this)
        providerSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(
                VoiceSettings.providerName(VoiceSettings.PROVIDER_VOLCENGINE),
                VoiceSettings.providerName(VoiceSettings.PROVIDER_BREEZE),
                VoiceSettings.providerName(VoiceSettings.PROVIDER_SYSTEM),
            ),
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        providerSpinner.setSelection(
            when (settings.provider) {
                VoiceSettings.PROVIDER_BREEZE -> 1
                VoiceSettings.PROVIDER_SYSTEM -> 2
                else -> 0
            }
        )
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                settings = settings.copy(
                    provider = when (pos) {
                        1 -> VoiceSettings.PROVIDER_BREEZE
                        2 -> VoiceSettings.PROVIDER_SYSTEM
                        else -> VoiceSettings.PROVIDER_VOLCENGINE
                    }
                )
                // 控件还没建好时（onCreate 里的 setSelection 会立刻回调）先跳过
                if (::voiceSpinner.isInitialized && ::breezeVoiceSpinner.isInitialized) renderVoicePicker()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(providerSpinner)

        root.addView(label("音色"))
        voiceSpinner = Spinner(this)
        root.addView(voiceSpinner)
        breezeVoiceSpinner = Spinner(this).apply { visibility = android.view.View.GONE }
        root.addView(breezeVoiceSpinner)
        root.addView(Button(this).apply {
            text = "刷新 Breeze 音色列表"
            setOnClickListener { refreshBreezeVoices() }
        })

        // 当前生效配置，一眼看清测的是谁
        cfgSummary = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 8, 0, 0)
        }
        root.addView(cfgSummary)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 24, 0, 8)
        }
        startBtn = Button(this).apply {
            text = "开始对话"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startLoop() }
        }
        stopBtn = Button(this).apply {
            text = "停止"; isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { stopLoop() }
        }
        row.addView(startBtn); row.addView(stopBtn)
        memoryBtn = Button(this).apply {
            text = "记忆 ${loop.memoryCount}"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showMemory() }
        }
        row.addView(memoryBtn)
        root.addView(row)

        // ---- 打字入口（不想开口时用；走同一条 对话→TTS→播放 链路）----
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
        }
        textInput = EditText(this).apply {
            hint = "打字测试：输入一句话，点发送（不用开口）"
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                ) {
                    sendText(); true
                } else false
            }
        }
        inputRow.addView(textInput)
        inputRow.addView(Button(this).apply {
            text = "发送"
            setOnClickListener { sendText() }
        })
        root.addView(inputRow)

        // ---- 隐私档位 + 记忆整理 ----
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 2, 0, 2)
        }
        privacyBtn = Button(this).apply {
            text = "隐私：${privacyLabel(settings.privacyLevel)}"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { cyclePrivacy() }
        }
        toolRow.addView(privacyBtn)
        toolRow.addView(Button(this).apply {
            text = "立即整理记忆"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { loop.curateMemoryNow() }
        })
        root.addView(toolRow)

        visionBtn = Button(this).apply {
            text = "看一眼我（前摄一拍）"
            setOnClickListener { lookAtMe() }
        }
        root.addView(visionBtn)

        status = TextView(this).apply {
            text = "点「开始对话」，然后直接说话"
            textSize = 16f; setPadding(0, 14, 0, 8)
        }
        root.addView(status)

        transcript = TextView(this).apply {
            textSize = 13f; setTextColor(0xFF333333.toInt()); setPadding(0, 6, 0, 6)
        }
        root.addView(transcript)

        // 调试：DS 实际读到的内容
        dsView = TextView(this).apply {
            textSize = 10f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 10, 0, 0)
            text = "DS 输入：尚未发起"
        }
        root.addView(dsView)

        dsReplyView = TextView(this).apply {
            textSize = 10f
            setTextColor(0xFF1B5E20.toInt())
            setPadding(0, 6, 0, 0)
            text = "DS 返回：尚未开始"
        }
        root.addView(dsReplyView)

        setContentView(ScrollView(this).apply { addView(root) })
        renderVoicePicker()

        capsText.text = ""
        refreshCaps()

        observe()
        val dsHandler = android.os.Handler(mainLooper)
        dsHandler.post(object : Runnable {
            override fun run() {
                val p = loop.lastPrompt
                dsView.text = if (p.isBlank()) "DS 输入：尚未发起"
                    else "DS 输入（" + p.length + " 字）：\n" + p.takeLast(1200)
                val r = loop.lastReply
                dsReplyView.text = if (r.isBlank()) "DS 返回：尚未开始"
                    else "DS 返回（" + r.length + " 字）：\n" + r
                dsHandler.postDelayed(this, 600)
            }
        })

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA,
                ),
                1001,
            )
        }

        intent?.getStringExtra("doubaoKey")?.takeIf { it.isNotBlank() }?.let { settings = settings.copy(doubaoKey = it) }
        intent?.getStringExtra("deepseekKey")?.takeIf { it.isNotBlank() }?.let { settings = settings.copy(deepseekKey = it) }
        // 桌宠手势：双击=文本入口，三击=语音入口
        if (intent?.getBooleanExtra("autoVoice", false) == true) {
            window.decorView.postDelayed({ startLoop() }, 700)
        }
        if (intent?.getBooleanExtra("autostart", false) == true) {
            window.decorView.postDelayed({ startLoop() }, 900)
        }
        // 供 adb 自动验证：--es autotext "你好呀"
        intent?.getStringExtra("autotext")?.takeIf { it.isNotBlank() }?.let { t ->
            textInput.setText(t)
            window.decorView.postDelayed({ sendText() }, 900)
        }
        // 供 adb 验证"记忆整理"：--ez curateNow true
        if (intent?.getBooleanExtra("curateNow", false) == true) {
            window.decorView.postDelayed({ loop.curateMemoryNow() }, 5000)
        }
        intent?.getStringExtra("autotext2")?.takeIf { it.isNotBlank() }?.let { t2 ->
            window.decorView.postDelayed({
                textInput.setText(t2)
                sendText()
            }, 4000)
        }
    }

    /**
     * 看一眼我：等 0.3 秒 → **连拍 3 张** → 让视觉模型给出**客观观察** → 把观察丢给人设去反应。
     * 图片不落盘、不上传第三方存储，只内联进一次请求。
     *
     * @param auto true = 语音里听到"看我一眼"自动触发（这时用户可能还在抬头，0.3 秒等待更重要）
     */
    private fun lookAtMe(auto: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1002)
            return
        }
        uiScope.launch {
            visionBtn.isEnabled = false
            visionBtn.text = if (auto) "听你的，看你一眼…" else "看一眼…"
            try {
                // 等 0.3 秒再拍：刚点完按钮／刚说完"看我一眼"时人往往还在抬头调整，
                // 立刻拍容易是糊的或者半个脑袋。
                kotlinx.coroutines.delay(SNAP_DELAY_MS)
                // 连拍 3 张一起交给视觉模型：一张糊了还有另外两张，也让它能对比前后表情
                val shots = cameraSnap.captureBurst(count = 3, intervalMs = 200)
                if (shots.isEmpty()) {
                    visionBtn.text = "没拍到，再试一次"
                    return@launch
                }
                    // 端点/key 统一由 ApiFactory.vision() 提供（不再本页硬编码 deepseek）
                    val observation = com.dshpet.android.api.ApiFactory.vision(this@VoiceLoopActivity).observe(shots)
                if (observation.isNullOrBlank()) {
                    visionBtn.text = "看不出什么"
                } else {
                    append("👀 $observation")
                    // 没在对话状态时先启动语音循环，否则只出文字不出声（自动开麦）
                    val shared = runCatching { com.dshpet.android.voice.core.VoiceRuntime.ensureStarted(this@VoiceLoopActivity) }.getOrNull()
                    if (shared != null) {
                        loop = shared
                        ownsLoop = false      // 同上：共享实例不能被本页释放
                    } else if (!com.dshpet.android.voice.core.VoiceLoop.running) {
                        runCatching { loop.start(settings) }
                    }
                    loop.submitText("[观察] $observation")
                }
            } finally {
                visionBtn.isEnabled = true
                visionBtn.text = "看一眼我（前摄一拍）"
            }
        }
    }

    /**
     * 把当前下拉框选中的音色落盘。
     *
     * **必须写 PetConfig**：`VoiceRuntime.buildSettings()` 用 PetConfig 覆盖 VoiceSettings，
     * 只写后者的话，下一次启动语音又回到默认音色（"音色改不了"的根因）。
     */
    private fun persistVoiceChoice() {
        // 按当前服务商落盘：豆包写 voiceId，Breeze 写 breezeVoiceId（store.save 会同步 PetConfig）
        settings = if (settings.provider == VoiceSettings.PROVIDER_BREEZE) {
            settings.copy(
                breezeVoiceId = breezeVoices.getOrNull(breezeVoiceSpinner.selectedItemPosition)?.first
                    ?: settings.breezeVoiceId,
            )
        } else {
            settings.copy(voiceId = settings.voiceId)
        }
        store.save(settings)
        updateCfgSummary()
    }

    /** 按当前服务商切换"音色"控件：豆包 = 443 条下拉；Breeze = 输入 ID；系统 = 隐藏 */
    private fun renderVoicePicker() {
        val doubao = com.dshpet.android.voice.DoubaoVoices.load(this)
        when (settings.provider) {
            VoiceSettings.PROVIDER_BREEZE -> {
                voiceSpinner.visibility = android.view.View.GONE
                breezeVoices = com.dshpet.android.voice.BreezeVoices.cached(this)
                breezeVoiceSpinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_item,
                    breezeVoices.map { it.second }.ifEmpty { listOf("（点下面按钮刷新音色列表）") },
                ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                breezeVoiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                        breezeVoices.getOrNull(pos)?.let { settings = settings.copy(breezeVoiceId = it.first) }
                        updateCfgSummary()
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
                breezeVoiceSpinner.setSelection(
                    breezeVoices.indexOfFirst { it.first == settings.breezeVoiceId }.coerceAtLeast(0)
                )
                breezeVoiceSpinner.visibility = android.view.View.VISIBLE
            }
            VoiceSettings.PROVIDER_SYSTEM -> {
                voiceSpinner.visibility = android.view.View.GONE
                breezeVoiceSpinner.visibility = android.view.View.GONE
            }
            else -> {
                voiceSpinner.visibility = android.view.View.VISIBLE
                breezeVoiceSpinner.visibility = android.view.View.GONE
                voiceSpinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_item,
                    doubao.map { it.second + " · " + it.third },
                ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                        if (pos in doubao.indices) {
                            settings = settings.copy(voiceId = doubao[pos].first)
                            updateCfgSummary()
                        }
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
                voiceSpinner.setSelection(doubao.indexOfFirst { it.first == settings.voiceId }.coerceAtLeast(0))
            }
        }
        updateCfgSummary()
    }

    /** 拉 Breeze 音色列表（UI 只显示名字，ID 只存在设置里） */
    private fun refreshBreezeVoices() {
        val key = settings.breezeKey
        if (key.isBlank()) {
            android.widget.Toast.makeText(this, "先到「语音 API」里填 Breeze Key", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        uiScope.launch {
            runCatching { com.dshpet.android.voice.BreezeVoices.fetch(applicationContext, key) }
                .onSuccess { renderVoicePicker() }
                .onFailure {
                    android.widget.Toast.makeText(
                        this@VoiceLoopActivity, "拉取失败：" + (it.message ?: "网络错误"),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }

    private fun updateCfgSummary() {
        val voice = if (settings.provider == VoiceSettings.PROVIDER_BREEZE) {
            com.dshpet.android.voice.BreezeVoices.nameOf(this, settings.breezeVoiceId)
        } else {
            com.dshpet.android.voice.DoubaoVoices.nameOf(this, settings.voiceId)
        }
        cfgSummary.text = "当前：服务商=" + VoiceSettings.providerName(settings.provider) +
            " 音色=" + voice +
            " 情绪=" + settings.emotion.ifBlank { "无" } + "/" + settings.emotionScale +
            " 语速=" + settings.speechRate + " 音量=" + settings.loudnessRate
    }

    /** 打字发送：走与语音相同的 对话 → TTS → 播放 链路 */
    private fun sendText() {
        val t = textInput.text.toString().trim()
        if (t.isBlank()) return
        textInput.setText("")
        persistVoiceChoice()
        loop.submitText(t, settings)
    }

    /** 订阅状态 + 事件（**Observer**：UI 只读，不驱动逻辑） */
    private fun observe() {
        uiScope.launch {
            loop.state.collect { s ->
                status.text = when (s) {
                    is VoiceState.Idle -> "已停止"
                    is VoiceState.Listening -> if (s.partial.isBlank()) "🎧 听你说…" else "🎧 ${s.partial}"
                    is VoiceState.Thinking ->
                        "🤔 思考中…\n" + s.answer.takeLast(160)
                    is VoiceState.Speaking -> "🔊 ${s.current}"
                    is VoiceState.Failed -> if (s.recoverable) "⚠️ ${s.reason}（重试中）" else "❌ ${s.reason}"
                }
            }
        }
        uiScope.launch {
            loop.events.collect { e ->
                when (e) {
                    is VoiceEvent.UserSaid -> {
                        append("🧑 ${e.text}")
                        // 说话里点名"看我一眼"→ 自动拍照走同一套视觉流程（20 秒内不重复，
                        // 免得一句"看看我"被 ASR 切成几段时连拍三次）
                        val said = e.text.orEmpty()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (wantsLook(said) && now - lastAutoLookMs > AUTO_LOOK_MIN_GAP_MS) {
                            lastAutoLookMs = now
                            append("👀 （听到「看我一眼」，正在拍）")
                            lookAtMe(auto = true)
                        }
                    }
                    is VoiceEvent.AssistantSaid -> {
                        append("🤖 ${e.text}")
                        // 记忆抽取/情绪更新都是异步的，晚一点再刷新展示
                        memoryBtn.postDelayed({ refreshMemoryLabel(); refreshCaps() }, 2500)
                    }
                    is VoiceEvent.Error -> append("⚠️ ${e.message}")
                    is VoiceEvent.Info -> append("ℹ️ ${e.message}")
                    is VoiceEvent.Segment -> {}
                }
            }
        }
    }

    private fun startLoop() {
        persistVoiceChoice()
        if (settings.doubaoKey.isBlank() || settings.deepseekKey.isBlank()) {
            status.text = "❌ 请先填两个 Key"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }
        transcript.text = ""
        startBtn.isEnabled = false
        stopBtn.isEnabled = true
        val shared = runCatching { com.dshpet.android.voice.core.VoiceRuntime.ensureStarted(this) }.getOrNull()
        if (shared != null) {
            loop = shared
            ownsLoop = false        // 共享实例：生命周期归 VoiceRuntime，不归本页
        } else {
            loop.start(settings)
        }
    }

    private fun stopLoop() {
        loop.stop()
        startBtn.isEnabled = true
        stopBtn.isEnabled = false
    }

    private fun append(line: String) {
        transcript.text = (transcript.text.toString() + "\n" + line).trim()
    }

    private fun refreshMemoryLabel() {
        memoryBtn.text = "记忆 ${loop.memoryCount}"
    }

    /** 刷设备能力 + 应用感知来源（双路：原生可用 / 降级）+ 桌宠情绪 */
    private fun refreshCaps() {
        val appSource = loop.appSourceId
        capsText.text = "设备能力：${loop.capabilities.asrSummary()} ｜ ${loop.capabilities.ttsSummary()}\n" +
            "应用感知：$appSource ｜ 隐私：${privacyLabel(settings.privacyLevel)}\n" +
            "桌宠状态：${loop.moodSummary}"
        if (appSource == "none") {
            usageHint.visibility = android.view.View.VISIBLE
            usageHint.text = "▸ 点这里开启「使用情况访问」，桌宠就能知道你正在用什么应用"
        } else {
            usageHint.visibility = android.view.View.GONE
        }
    }

    private fun privacyLabel(level: Int): String = when (level) {
        0 -> "关闭"
        1 -> "基础"
        else -> "详细"
    }

    /** 隐私档位：关闭 → 基础 → 详细 → 关闭 */
    private fun cyclePrivacy() {
        val next = (settings.privacyLevel + 1) % 3
        settings = settings.copy(privacyLevel = next)
        store.save(settings)
        loop.setPrivacyLevel(next)
        privacyBtn.text = "隐私：${privacyLabel(next)}"
        refreshCaps()
    }

    /** 查看/清空长期记忆（演示用，正式版应做成"记忆管理"页） */
    private fun showMemory() {
        val items = loop.memories()
        val segs = loop.memorySegments()
        val body = buildString {
            if (items.isEmpty() && segs.isEmpty()) {
                append("还没有记忆。多聊几句，它会自动把稳定偏好、习惯、事实记下来。")
            } else {
                if (items.isNotEmpty()) {
                    append("【长期事实 ${items.size} 条】\n")
                    append(items.sortedByDescending { it.importance }.take(30)
                        .joinToString("\n") { "· ${it.type.label}｜${it.text}（重要度 ${"%.1f".format(it.importance)}，命中 ${it.hitCount}）" })
                }
                if (segs.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("【中期话题 ${segs.size} 条】\n")
                    append(segs.sortedByDescending { it.heat }.take(10)
                        .joinToString("\n") { "· ${it.title}（热度 ${it.heat}）\n  ${it.summary}" })
                }
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("长期记忆（${items.size} 事实 / ${segs.size} 话题）")
            .setMessage(body)
            .setPositiveButton("关闭", null)
            .setNeutralButton("立即整理") { _, _ -> loop.curateMemoryNow() }
            .setNegativeButton("清空") { _, _ ->
                loop.clearMemory()
                refreshMemoryLabel()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            status.text = "❌ 没有录音权限：请到系统设置里给「dsh-pet 桌宠」开启麦克风"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logJob?.cancel()
        uiScope.coroutineContext[Job]?.cancel()
        // 只有自己 new 出来的那个才释放；共享实例（桌宠悬浮窗在用）释放了会把它的语音会话一起杀掉。
        if (ownsLoop) {
            runCatching {
                loop.stop()
                loop.release()
            }
        }
    }
}
