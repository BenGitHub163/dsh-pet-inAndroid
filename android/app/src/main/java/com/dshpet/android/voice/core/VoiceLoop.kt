package com.dshpet.android.voice.core

import android.content.Context
import android.util.Log
import com.dshpet.android.chat.SseClient
import com.dshpet.android.voice.SentenceSplitter
import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsEngines
import com.dshpet.android.voice.TtsProvider
import com.dshpet.android.voice.VoiceProfile
import com.dshpet.android.voice.asr.AsrProvider
import com.dshpet.android.voice.asr.SystemAsrProvider
import com.dshpet.android.voice.chat.ChatClient
import com.dshpet.android.voice.memory.MemoryManager
import com.dshpet.android.voice.providers.SystemTtsProvider
import com.dshpet.android.voice.providers.VolcengineTtsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音对话编排器（**Facade + 状态机 + 依赖倒置**）。
 *
 * 上层（Activity/Service/桌宠）只做两件事：
 *   - `loop.state` 订阅状态，渲染 UI；
 *   - `loop.start(settings)` / `loop.stop()`。
 *
 * 内部把四件事解耦，每一件都能单独替换：
 *   1. 听：`AsrProvider`（Strategies）
 *   2. 想：`ChatClient`（适配 SseClient → Flow）
 *   3. 说：`TtsChain`（责任链：豆包 → 系统 TTS）
 *   4. 放：`SpeechPlayer` + `SpeechQueue`（复用 AudioTrack、逐句流水线）
 */
class VoiceLoop(context: Context) {

    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<VoiceEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    /** 设备能力（首次访问时探测一次） */
    val capabilities: VoiceCapabilities by lazy { VoiceCapabilities.detect(appCtx) }

    private val asr: AsrProvider by lazy { SystemAsrProvider(appCtx, capabilities) }
    private val chat = ChatClient()
    private val player = SpeechPlayer(appCtx)

    /** 预取式播放流水线：合成下一句的同时播当前句 */
    private val pipeline = SpeechPipeline(scope, player)

    /** 长期记忆（抽取 → 合并 → 检索 → 注入）。注意 maxTokens 给足：抽取和整理都要输出完整 JSON */
    private val memory by lazy { MemoryManager(appCtx, chat) { buildChatCfg(900) } }

    /** 设备环境（时间/位置/电量/网络），每 30s 取一次 */
    private val deviceContext = com.dshpet.android.voice.context.DeviceContextProvider(appCtx)

    /** 性格（Character Card V2 结构）+ 情绪状态（好感/心情/恼火） */
    private val persona = com.dshpet.android.voice.persona.PetPersona()
    private val moodStore = com.dshpet.android.voice.persona.PetMoodStore(appCtx)
    private var mood = com.dshpet.android.voice.persona.PetMood.NEUTRAL

    /** 最近一次发给 DS 的完整内容（调试页展示） */
    @Volatile
    var lastPrompt: String = ""
        private set

    /** 最近一次 DS 返回的原文（流式累积） */
    @Volatile
    var lastReply: String = ""
        private set

    val moodSummary: String
        get() = "好感 ${mood.affection}｜心情 ${mood.mood}｜恼火 ${mood.annoyance}"

    fun setPrivacyLevel(level: Int) {
        val v = level.coerceIn(0, 2)
        settings = settings.copy(privacyLevel = v)
        // 同步进唯一数据源，否则设置监听器会把这次修改改回去
        runCatching { VoiceSettingsStore.get(appCtx).save(settings) }
    }

    /** 给 UI 用的记忆入口 */
    val memoryCount: Int get() = memory.count() + memory.segmentCount()
    fun memories() = memory.all()
    fun memorySegments() = memory.allSegments()
    fun clearMemory() {
        memory.clear()
        memory.clearSegments()
        _events.tryEmit(VoiceEvent.Info("已清空记忆"))
    }

    /** 手动触发记忆整理（量化 + 模型评估修订） */
    fun curateMemoryNow() {
        _events.tryEmit(VoiceEvent.Info("开始整理记忆…"))
        memory.curateAsync(force = true)
    }

    /** 前台应用感知用的是哪条路（usage-stats / none） */
    val appSourceId: String get() = deviceContext.appSourceId()

    fun openUsageAccessSettings() = deviceContext.openUsageAccessSettings()

    private var settings = VoiceSettings()

    /** 监听设置变化（UI 一改，下一句就用新音色/新语速） */
    private var settingsJob: Job? = null
    private var job: Job? = null

    /**
     * 当前会话的 TTS 链路。`stop()` 时要靠它掐断"已发出但还没回来"的合成请求——
     * 否则 UI 显示已停止，OkHttp 请求仍在跑、仍在计费。
     */
    @Volatile
    private var activeTts: TtsProvider? = null

    private val history = ArrayDeque<SseClient.Msg>()

    /** 用户最后一次「真的说话」的时刻（用于沉默判定） */
    private var lastSpeechAt = 0L

    /** 上一条 ASR 提示 + 时间戳：同一句 5 秒内只报一次，避免刷屏 */
    private var lastAsrInfo: String = ""
    private var lastAsrInfoAt: Long = 0L

    /** 从上次用户说话以来，已经主动开口几次（用于轮换提示词 + 递增间隔） */
    private var nudgesSinceSpeech = 0

    /** 对话轮次：决定本轮用哪套说话风格（VoiceSkills 三套轮换） */
    private var turnIndex = 0

    // ---------------- 对外 ----------------

    /**
     * **配置热更新**：订阅 [VoiceSettingsStore.flow]。
     *
     * 联动：设置页（VoiceApiActivity）里改任何一项 → flow 发射 → 这里改 [settings]
     *       → [buildTts] 是**每句话**现算的，所以下一句立刻用新音色/新语速/新情绪。
     * 解耦：VoiceLoop 不认识设置页，只认识这条 flow。
     */
    private fun startSettingsWatcher() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            VoiceSettingsStore.get(appCtx).flow.collect { nv ->
                if (nv == settings) return@collect
                val providerChanged = nv.doubaoKey != settings.doubaoKey ||
                    nv.resourceId != settings.resourceId ||
                    nv.voiceId != settings.voiceId ||
                    nv.format != settings.format ||
                    nv.sampleRate != settings.sampleRate ||
                    nv.provider != settings.provider
                settings = nv
                com.dshpet.android.util.AppLog.log(
                    "VOICE",
                    "配置热更新：provider=" + nv.provider + " voice=" + nv.voiceId +
                        " 情绪=" + nv.emotion + "/" + nv.emotionScale +
                        " 语速=" + nv.speechRate + " 音量=" + nv.loudnessRate +
                        " 换声源=" + providerChanged,
                )
            }
        }
    }

    @com.dshpet.android.util.TraceLog(module = "VOICE", level = "I")
    fun start(s: VoiceSettings) {
        if (job?.isActive == true) return
        settings = s
        history.clear()
        turnIndex = 0
        mood = moodStore.load().decayed(System.currentTimeMillis())
        memory.onCurated = { msg -> _events.tryEmit(VoiceEvent.Info(msg)) }
        running = true
        current = this
        job = scope.launch { runLoop() }
        startSettingsWatcher()
    }

    @com.dshpet.android.util.TraceLog(module = "VOICE", level = "I")
    fun stop() {
        // 顺序很重要：先掐掉在途的合成请求（TtsChain → Volcengine 的 OkHttp call / 系统 TTS），
        // 再让播放器令牌失效并立刻静音。少了第一步，"停止"之后仍在计费。
        runCatching { activeTts?.cancel() }
        job?.cancel()
        job = null
        settingsJob?.cancel()
        settingsJob = null
        player.abort()          // 令牌失效 + 立刻静音，旧音频再也写不进来
        running = false
        _state.value = VoiceState.Idle
    }

    fun release() {
        stop()
        runCatching { asr.release() }      // 复用过的识别器要显式释放
        player.release()
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * 文字输入（测试/静音场景）：跳过 ASR，直接跑
     *   用户文本 → DeepSeek 流式回复 → 分句 → TTS → 播放 → 抽取记忆
     * 语音循环会被停下来，避免麦克风和播放打架。
     */
    @com.dshpet.android.util.TraceLog(module = "VOICE", level = "D")
    fun submitText(text: String, override: VoiceSettings? = null) {
        val t = text.trim()
        if (t.isBlank()) return
        if (override != null) settings = override
        stop()
        if (settings.doubaoKey.isBlank() && settings.deepseekKey.isBlank()) {
            fail("请先填 Keys（至少 DeepSeek key；豆包 key 决定要不要出声）", fatal = true)
            return
        }
        val tts = buildTts()
        val mock = settings.deepseekKey.isBlank()
        job = scope.launch {
            try {
                oneTurn(t, tts, mock, from = "text")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e   // 被打断：静默结束
                Log.w(TAG, "submitText failed: ${e.message}")
                _events.tryEmit(VoiceEvent.Error(e.message ?: "发送失败"))
            } finally {
                _state.value = VoiceState.Idle
            }
        }
    }

    // ---------------- 主循环 ----------------

    private suspend fun runLoop() {
        if (!asr.isAvailable()) {
            fail("这台手机没有可用的语音识别服务（可安装语音识别引擎后重试）", fatal = true)
            return
        }
        // 麦克风权限：必须确认"真的授了"，否则下面会每 600ms 空转一次，
        // 听感上就是"收音断断续续"，而且只在切到别的应用时明显（一次性授权会立刻失效）。
        val micOk = appCtx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micOk) {
            fail(
                "没有麦克风权限：权限弹窗里有「仅本次允许」，切到别的应用就失效——请重新授权并选「始终允许」",
                fatal = true,
            )
            return
        }
        // 用工厂判断"当前服务商"是否就绪：Breeze 只要 breezeKey+voiceId，不该要求豆包 Key
        if (!com.dshpet.android.voice.VoiceTtsFactory.isReady(settings)) {
            fail("语音服务未配置：先到「语音 API」里填好 Key 和音色", fatal = true)
            return
        }
        // 没填 DeepSeek Key 时用本地模拟回复：这样 ASR + TTS 两端可以先验证通
        val mock = settings.deepseekKey.isBlank()
        if (mock) {
            _events.tryEmit(VoiceEvent.Info("未填 DeepSeek Key，使用本地模拟回复（只听链路，不接模型）"))
        }
        val tts = buildTts()

        val voice = com.dshpet.android.voice.VoiceTtsFactory.profileFor(settings)
        lastSpeechAt = System.currentTimeMillis()
        nudgesSinceSpeech = 0

        while (currentCoroutineContext().isActive) {
            _state.value = VoiceState.Listening()
            val result = asr.listen(
                locale = settings.locale,
                // 兜底超时：静音探测在个别 ROM 上不触发时，别让"我在听"干挂 20 秒
                timeoutMs = 9_000,
                // 静音探测：1.4s 内一点声音都没有就立刻收一轮（空结果），
                // 否则系统识别器要 ~4s 才返回 NO_MATCH，"主动开口"就永远快不起来。
                probeMs = NudgePolicy.PROBE_SILENCE_MS,
                onPartial = { partial -> _state.value = VoiceState.Listening(partial) },
            )
            val userText = result.getOrNull()?.trim().orEmpty()

            if (userText.isBlank()) {
                // 没说话（或没听清）：accumulate 沉默时间，到点让模型主动开口
                result.exceptionOrNull()?.message?.let { msg ->
                    Log.i(TAG, "asr: $msg")
                    // 同一句话 5 秒内只提示一次：原先每轮都往对话里塞一行，看着像"一直在报错"
                    val now = System.currentTimeMillis()
                    if (msg != lastAsrInfo || now - lastAsrInfoAt > 5000) {
                        lastAsrInfo = msg
                        lastAsrInfoAt = now
                        _events.tryEmit(VoiceEvent.Info(msg))
                    }
                }
                val idleMs = System.currentTimeMillis() - lastSpeechAt
                val needMs = NudgePolicy.intervalMs(nudgesSinceSpeech)
                if (idleMs >= needMs) {
                    if (nudgesSinceSpeech >= NudgePolicy.MAX_NUDGES) {
                        // 收尾也可能失败（TTS 挂了/没网）：失败也必须退出，否则"我在听"永远挂着
                        runCatching { sayFarewellThenStop(tts, voice) }
                            .onFailure { Log.w(TAG, "farewell failed: ${it.message}") }
                        stop()
                        break
                    }
                    // 用户日志（User_语音）留痕：验证"沉默多久后主动开口"全靠这行
                    com.dshpet.android.util.AppLog.u(
                        "语音",
                        "沉默 ${idleMs}ms ≥ 阈值 ${needMs}ms → 第 ${nudgesSinceSpeech + 1} 次主动开口",
                    )
                    // 主动开口失败不能中断循环：计数照加，最终仍会走到收尾
                    runCatching { nudge(tts, voice) }
                        .onFailure { Log.w(TAG, "nudge failed: ${it.message}") }
                    nudgesSinceSpeech++
                    lastSpeechAt = System.currentTimeMillis()
                } else {
                    // 别空转：200ms 的间隔会让识别器"刚停又起"，国产 ROM 上直接表现为断续
                    kotlinx.coroutines.delay(600)
                }
                continue
            }

            lastSpeechAt = System.currentTimeMillis()
            nudgesSinceSpeech = 0
            oneTurn(userText, tts, mock)
        }
    }

    // ---------------- 沉默时主动开口 ----------------

    /**
     * 用户沉默 → 让 DeepSeek 按 [NudgePolicy] 里的一套人格主动说一句话。
     * 注意：提示词通过 system 消息下发，不污染历史里的 user 内容。
     */
    private suspend fun nudge(tts: TtsProvider, voice: VoiceProfile) {
        val tpl = NudgePolicy.templateFor(nudgesSinceSpeech)
        _events.tryEmit(VoiceEvent.Info("主动开口（${tpl.label}）"))
        val messages = buildList {
            add(SseClient.Msg("system", VoiceSkills.forNudge(tpl.system)))
            addAll(history.takeLast(6))
            if (history.isEmpty()) add(SseClient.Msg("user", "（用户还没开口）"))
        }
        val text = collectOneShot(messages, maxTokens = 64)
        if (text.isBlank()) return

        Log.i(TAG, "nudge[${tpl.id}]: $text")
        history.addLast(SseClient.Msg("assistant", text))
        _events.tryEmit(VoiceEvent.AssistantSaid(text))
        speakText(tts, voice, text, userText = "（沉默）")
        // 被冷落了：会有点小情绪（会随时间衰减，不会记仇）
        mood = moodStore.apply(mood, com.dshpet.android.voice.persona.MoodEvent.IGNORED)
    }

    /** 连续几次都没人理 → 说句收尾话再退出，别一直唠叨 */
    private suspend fun sayFarewellThenStop(tts: TtsProvider, voice: VoiceProfile) {
        _events.tryEmit(VoiceEvent.AssistantSaid(NudgePolicy.FAREWELL))
        speakText(tts, voice, NudgePolicy.FAREWELL, userText = "（沉默）")
        stop()
    }

    /** 一次性拿完整回复（非流式收集），用于主动开口这种短句 */
    private suspend fun collectOneShot(messages: List<SseClient.Msg>, maxTokens: Int): String {
        val sb = StringBuilder()
        runCatching {
            chat.stream(buildChatCfg(maxTokens), messages).collect { sb.append(it) }
        }.onFailure { Log.w(TAG, "nudge chat failed: ${it.message}") }
        return sb.toString().trim().removeSurrounding("\"", "\"").trim()
    }

    /** 把一段文本按句切开依次朗读（主动开口/收尾用，走同一条预取流水线） */
    private suspend fun speakText(tts: TtsProvider, voice: VoiceProfile, text: String, userText: String) {
        val splitter = SentenceSplitter(maxChunk = ReplyLengthPolicy.chunkSize(ReplyLength.SHORT))
        val pieces = buildList {
            addAll(splitter.feed(text))
            addAll(splitter.flush())
        }.ifEmpty { listOf(text) }
        val session = player.startSession(settings.sampleRate)
        val ch = Channel<String>(capacity = 4)
        val job = scope.launch(Dispatchers.IO) {
            pipeline.run(
                ch,
                session = session,
                synthesize = { t, onPcm ->
                    tts.synthesize(t, voice) { c ->
                        c.pcm?.let { onPcm(it, c.sampleRate) }
                        c.encoded?.let { player.playEncodedBlocking(it) }
                    }
                },
                onSegment = { seg, _ -> _state.value = VoiceState.Speaking(userText, text, seg) },
            )
        }
        pieces.forEach { ch.trySend(it) }
        ch.close()
        job.join()
    }

    private suspend fun oneTurn(userText: String, tts: TtsProvider, mock: Boolean = false, from: String = "voice") {
        history.addLast(SseClient.Msg("user", userText))
        while (history.size > settings.historyLimit) history.removeFirst()
        _events.tryEmit(VoiceEvent.UserSaid(userText))
        _state.value = VoiceState.Thinking(userText)

        val voice = com.dshpet.android.voice.VoiceTtsFactory.profileFor(settings)
        var answer = ""

        val length = ReplyLengthPolicy.hintFor(userText)
        val splitter = SentenceSplitter(maxChunk = ReplyLengthPolicy.chunkSize(length))
        val session = player.startSession(settings.sampleRate)
        val segments = Channel<String>(capacity = 8)
        val pipelineJob = scope.launch(Dispatchers.IO) {
            pipeline.run(
                segments,
                session = session,
                synthesize = { text, onPcm ->
                    tts.synthesize(text, voice) { chunk ->
                        chunk.pcm?.let { onPcm(it, chunk.sampleRate) }
                        chunk.encoded?.let { player.playEncodedBlocking(it) }
                    }
                },
                onSegment = { seg, _ -> _state.value = VoiceState.Speaking(userText, answer, seg) },
            )
        }
        // 情绪：先根据用户这句话更新状态，让她这轮的反应带上情绪
        val moodEvent = com.dshpet.android.voice.persona.MoodEventDetector.detect(userText)
        mood = moodStore.apply(mood, moodEvent)

        // 每轮注入：性格与情绪 + 说话风格 + 长度期望 + 设备环境 + 分层记忆
        val memos = memory.retrieveTiered(userText)
        val env = try {
            deviceContext.promptBlock(level = settings.privacyLevel)
        } catch (e: Throwable) {
            ""
        }
        val systemText = buildString {
            append(com.dshpet.android.voice.persona.PersonaPrompts.block(persona, mood))
            append("\n\n")
            append(VoiceSkills.systemFor(turnIndex))
            append("\n\n【硬约束】不要输出 null / None / undefined，也不要输出字段名或 JSON 片段；")
            append("某条信息缺失时就不提它，绝不能用 null 代替。")
            append("\n\n")
            append(ReplyLengthPolicy.systemLine(length))
            if (env.isNotBlank()) {
                append("\n\n")
                append(env)
            }
            if (!memos.isEmpty) {
                append("\n\n")
                append(memory.promptBlock(memos))
            }
        }
        val messages = buildList {
            add(SseClient.Msg("system", systemText))
            addAll(history)
        }
        lastReply = ""
        lastPrompt = "【system】\n" + systemText + "\n\n【user】\n" + userText
        android.util.Log.i("DshPrompt", "SYSTEM>>> " + systemText.replace("\n", " | ").take(900))
        turnIndex++
        if (!memos.isEmpty) {
            _events.tryEmit(
                VoiceEvent.Info("想起 ${memos.size} 条记忆（${memos.facts.size} 事实 / ${memos.segments.size} 话题）")
            )
        }
        val source: kotlinx.coroutines.flow.Flow<String> =
            if (mock) mockReply(userText) else chat.stream(buildChatCfg(ReplyLengthPolicy.maxTokens(length)), messages)
        try {
            source.collect { delta ->
                val sanitizeDelta = delta
                .replace("null", "", ignoreCase = true)
                .replace("undefined", "", ignoreCase = true)
                .replace("none", "", ignoreCase = true)
            answer += sanitizeDelta
            lastReply = answer
                _state.value = VoiceState.Thinking(userText, answer)
                splitter.feed(sanitizeDelta).forEach { segments.trySend(it) }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e   // 用户打断 = 正常路径，不报错
            Log.w(TAG, "chat: ${e.message}")
            _events.tryEmit(VoiceEvent.Error(e.message ?: "对话失败"))
        } finally {
            splitter.flush().forEach { segments.trySend(it) }
            segments.close()
            pipelineJob.join()
        }

        if (answer.isNotBlank()) {
            _events.tryEmit(VoiceEvent.AssistantSaid(answer))
            history.addLast(SseClient.Msg("assistant", answer))
            while (history.size > settings.historyLimit) history.removeFirst()
            // 异步抽取记忆（带上"当时在哪、在用什么应用"，供后续整理与回忆）
            val facts = runCatching { deviceContext.facts() }.getOrNull()
            memory.rememberAsync(
                userText,
                answer,
                com.dshpet.android.voice.memory.MemoryContext(
                    place = facts?.place,
                    lat = facts?.lat,
                    lng = facts?.lng,
                    app = facts?.app,
                    source = from,
                ),
            )
            // 每 6 轮做一次「短期 → 中期」整理（话题片段 + heat）
            if (history.size % 6 == 0) memory.consolidateAsync(history.toList())
            // 记忆自省：累加轮次，到点（24h / 40 轮 / 40 条）让模型评估修订
            memory.onTurn()
            memory.curateAsync()
            kotlinx.coroutines.delay(200)
        }
        _state.value = VoiceState.Listening()
    }

    // ---------------- 合成 + 播放 ----------------

    private suspend fun speakSegment(
        tts: TtsProvider,
        voice: VoiceProfile,
        text: String,
        userText: String,
        answer: String,
    ) = withContext(Dispatchers.IO) {
        _state.value = VoiceState.Speaking(userText, answer, text)
        _events.tryEmit(VoiceEvent.Segment(text))

        var pcmStarted = false
        try {
            tts.synthesize(text, voice) { chunk ->
                chunk.pcm?.let { pcm ->
                    if (!pcmStarted) {
                        player.begin(chunk.sampleRate)
                        pcmStarted = true
                    }
                    player.write(pcm)
                }
                chunk.encoded?.let { enc ->
                    // 降级到系统 TTS 时拿到的 WAV：一次性播完
                    player.playEncodedBlocking(enc, if (settings.format == "mp3") "mp3" else "wav")
                }
            }
            if (pcmStarted) {
                player.awaitDrain()
                player.finishSegment()
            }
            Unit
        } catch (e: Throwable) {
            Log.w(TAG, "speak segment failed: ${e.message}")
            _events.tryEmit(VoiceEvent.Error("合成失败：${e.message}"))
        }
    }

    /** 本地模拟回复：没有 DeepSeek Key 时也能验证 ASR → TTS 两端链路 */
    private fun mockReply(userText: String): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow {
            val s = "我听到你说，$userText。这是本地生成的测试回复，用来验证识别和声音。你现在听到的，就是最终的效果。"
            for (part in s.chunked(5)) {
                emit(part)
                kotlinx.coroutines.delay(35)
            }
        }

    // ---------------- 构建依赖 ----------------

    /** 情绪 → 语速/音量：让"生气"在音色上也听得出来 */
    private fun moodVoiceParams(): Pair<Int, Int> = when {
        mood.annoyance >= 50 -> 18 to -6
        mood.mood <= -40 -> -14 to -10
        mood.mood >= 50 -> 10 to 4
        else -> 0 to 0
    }

    private fun buildTts(): TtsProvider {
        // **所有非测试路径都走 VoiceTtsFactory**：用户在「语音 API」里选了哪家
        //（豆包 / Breeze / 系统），这里就建哪家；心情偏移只对豆包生效，其它家忽略。
        val (moodRate, moodLoud) = moodVoiceParams()
        val primary = com.dshpet.android.voice.VoiceTtsFactory.create(
            appCtx, settings, moodRate, moodLoud,
        )
        val rate = if (settings.provider == VoiceSettings.PROVIDER_VOLCENGINE) settings.sampleRate else 24000
        val cached = CachingTtsProvider(appCtx, primary, expectedRate = rate)
        val system = SystemTtsProvider(appCtx)
        val chain = TtsChain(listOf(cached, system))
        activeTts = chain          // stop() 靠它掐断在途请求（见 stop() 的注释）
        return chain
    }

    private fun buildChatCfg(maxTokens: Int = 512) =
        com.dshpet.android.api.ApiFactory.chat(appCtx, maxTokens = maxTokens)

    private fun fail(reason: String, fatal: Boolean) {
        Log.e(TAG, reason)
        _events.tryEmit(VoiceEvent.Error(reason, fatal))
        _state.value = VoiceState.Failed(reason, recoverable = !fatal)
    }

    companion object {
        private const val TAG = "DshVoiceLoop"

        /** 语音对话是否正在运行（供桌宠手势判断"单击退出"） */
        @Volatile
        var running: Boolean = false
            private set

        /** 当前实例，供外部停止 */
        @Volatile
        var current: VoiceLoop? = null
            private set
    }
}
