package com.dshpet.android.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshpet.android.R
import com.dshpet.android.api.TtsApi
import com.dshpet.android.util.ProvideComposeHost
import com.dshpet.android.voice.core.VoiceLoop
import com.dshpet.android.voice.core.VoiceSettings
import com.dshpet.android.voice.core.VoiceSettingsStore
import com.dshpet.android.voice.core.VoiceRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **语音 API 二级页**（AI 对话 → 语音 API → 这里）。
 *
 * 结构（用户要求"先选厂商，再进二级页面"）：
 *   一级：服务商列表（豆包 / Breeze Blue / 系统 TTS）
 *   二级：该服务商自己的参数页
 *         · 豆包：Key / Resource-Id / 音色（443 条，可搜索）/ 情绪 / 情绪强度 /
 *                 语速 / 音量 / 格式 / 采样率  —— 与「测试 → TTS 试音台」对齐
 *         · Breeze：Key / 音色 ID（可拉取列表）/ 试听
 *         · 系统 TTS：只读说明
 *
 * 所有改动即时写入 [VoiceSettingsStore]；如果语音会话正在跑，退出时停掉，
 * 下一次开口就用新音色（和 AI 对话页原来的行为一致）。
 */
class VoiceApiActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ProvideComposeHost { VoiceApiRoot() }
        }
    }

    // 注：这里**不再**停语音会话——VoiceLoop 订阅了 VoiceSettingsStore.flow，
    // 设置一改下一句就生效（热更新），不需要重启会话。
}

private enum class Page { LIST, DOUBAO, BREEZE, SYSTEM }

@Composable
private fun VoiceApiRoot() {
    val ctx = LocalContext.current
    val store = remember { VoiceSettingsStore.get(ctx) }
    var settings by remember { mutableStateOf(store.load()) }
    var page by remember { mutableStateOf(Page.LIST) }

    fun persist(next: VoiceSettings) {
        settings = next
        store.save(next)
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- 顶栏 ----
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page == Page.LIST) {
                    Spacer(Modifier.size(8.dp))
                    Text("语音 API", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                } else {
                    TextButton(onClick = { page = Page.LIST }) {
                        androidx.compose.material3.Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        Text("返回")
                    }
                    Text(
                        VoiceSettings.providerName(settings.provider),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Divider()
            when (page) {
                Page.LIST -> ProviderListPage(settings, onPick = { p ->
                    persist(settings.copy(provider = p))
                }, onOpen = { p ->
                    // 点卡片 = 选中 + 直接进二级页
                    persist(settings.copy(provider = p))
                    page = when (p) {
                        VoiceSettings.PROVIDER_VOLCENGINE -> Page.DOUBAO
                        VoiceSettings.PROVIDER_BREEZE -> Page.BREEZE
                        else -> Page.SYSTEM
                    }
                })
                Page.DOUBAO -> DoubaoPage(settings, onBack = { page = Page.LIST }) { persist(it) }
                Page.BREEZE -> BreezePage(settings, onBack = { page = Page.LIST }) { persist(it) }
                Page.SYSTEM -> SystemPage()
            }
        }
    }
}

// ============================================================== 一级：服务商

@Composable
private fun ProviderListPage(cur: VoiceSettings, onPick: (String) -> Unit, onOpen: (String) -> Unit) {
    val items = listOf(
        Triple(VoiceSettings.PROVIDER_VOLCENGINE, "豆包（火山引擎）", "音色 443 个，支持情绪/语速/音量，延迟低"),
        Triple(VoiceSettings.PROVIDER_BREEZE, "Breeze Blue", "国外 TTS，音色可从账号拉取，支持试听"),
        Triple(VoiceSettings.PROVIDER_SYSTEM, "系统 TTS", "不联网，任何机型可用，音色由系统决定"),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(
            "选择用哪家做语音合成。选好后进入它的二级页面填参数。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        items.forEach { (id, name, desc) ->
            val selected = cur.provider == id
            Card(
                onClick = { onOpen(id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { onPick(id) })
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ============================================================== 二级：豆包

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoubaoPage(
    s: VoiceSettings,
    onBack: () -> Unit,
    onSave: (VoiceSettings) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val voices = remember { loadDoubaoVoices(ctx) }
    var query by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val filtered = remember(query, voices) {
        if (query.isBlank()) voices
        else voices.filter {
            it.second.contains(query, true) || it.first.contains(query, true) || it.third.contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        OutlinedTextField(
            value = s.doubaoKey,
            onValueChange = { onSave(s.copy(doubaoKey = it)) },
            label = { Text("豆包 API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        Label("X-Api-Resource-Id（决定 1.0/2.0/复刻，也决定计费商品）")
        Chips(
            list = listOf("seed-tts-2.0", "seed-tts-1.0", "seed-icl-2.0", "seed-icl-1.0"),
            selected = s.resourceId,
            onSelect = { onSave(s.copy(resourceId = it)) },
        )

        Spacer(Modifier.height(12.dp))
        Label("音色（共 ${voices.size} 个，可搜 id / 名字 / 分类）")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索：女声 / 男声 / 儿童 / vv …") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
            items(filtered) { v ->
                val (id, name, tag) = v
                val sel = id == s.voiceId
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = sel, onClick = { onSave(s.copy(voiceId = id)) })
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        Text(tag, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Label("情绪 emotion（不选 = 不带情绪）")
        Chips(
            list = listOf("", "happy", "sad", "angry", "surprised", "fear", "hate", "excited", "neutral"),
            selected = s.emotion,
            labelOf = { if (it.isBlank()) "无" else it },
            onSelect = { onSave(s.copy(emotion = it)) },
        )

        Spacer(Modifier.height(10.dp))
        SliderRow("情绪强度 emotion_scale", s.emotionScale, 1f..5f, steps = 3) {
            onSave(s.copy(emotionScale = it))
        }
        SliderRow("语速 speech_rate（-50 = 0.5×，100 ≈ 2×）", s.speechRate, -50f..100f) {
            onSave(s.copy(speechRate = it))
        }
        SliderRow("音量 loudness_rate（-50 … 100）", s.loudnessRate, -50f..100f) {
            onSave(s.copy(loudnessRate = it))
        }

        Spacer(Modifier.height(6.dp))
        Label("输出格式")
        Chips(listOf("pcm", "mp3"), s.format) { onSave(s.copy(format = it)) }
        Spacer(Modifier.height(6.dp))
        Chips(listOf("16000", "24000", "48000"), s.sampleRate.toString()) {
            onSave(s.copy(sampleRate = it.toIntOrNull() ?: 24000))
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    if (!com.dshpet.android.data.KeyFactory.current().hasDoubao) {
                        msg = "先填 Key"
                        return@OutlinedButton
                    }
                    previewing = true
                    msg = "合成中…"
                    scope.launch {
                        val r = runCatching { TtsPreview.play(doubaoPreviewCfg(s), doubaoPreviewVoice(s)) }
                        previewing = false
                        msg = r.fold({ "播放完成" }, { e -> "失败：${e.message}" })
                    }
                },
                enabled = !previewing,
            ) { Text(if (previewing) "播放中…" else "试听当前音色") }
            Spacer(Modifier.size(10.dp))
            if (msg.isNotBlank()) Text(msg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================== 二级：Breeze

@Composable
private fun BreezePage(
    s: VoiceSettings,
    onBack: () -> Unit,
    onSave: (VoiceSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // 先用缓存（离线也能看到名字），点「刷新」再去服务端拉
    var list by remember {
        mutableStateOf(
            BreezeVoices.cached(ctx).map { Triple(it.first, it.second, "") },
        )
    }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        OutlinedTextField(
            value = s.breezeKey,
            onValueChange = { onSave(s.copy(breezeKey = it)) },
            label = { Text("Breeze API Key（brz_ 开头）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        // 用户只认名字：这里只展示选中的音色名，ID 藏在设置里（不暴露）
        OutlinedTextField(
            value = if (s.breezeVoiceId.isBlank()) "未选音色" else BreezeVoices.nameOf(ctx, s.breezeVoiceId),
            onValueChange = {},
            readOnly = true,
            label = { Text("当前音色") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    if (!com.dshpet.android.data.KeyFactory.current().hasBreeze) { msg = "先填 Key"; return@OutlinedButton }
                    loading = true; msg = "拉取中…"
                    scope.launch {
                        val r = runCatching {
                            BreezeVoices.fetch(ctx).map { Triple(it.first, it.second, "") }
                        }
                        loading = false
                        r.onSuccess { got ->
                            list = got
                            msg = "共 " + got.size + " 个音色"
                            if (s.breezeVoiceId.isBlank()) got.firstOrNull()?.let { onSave(s.copy(breezeVoiceId = it.first)) }
                        }.onFailure { e -> msg = "失败：" + (e.message ?: "网络错误") }
                    }
                },
                enabled = !loading,
            ) { Text(if (loading) "拉取中…" else "刷新音色列表") }
            Spacer(Modifier.size(10.dp))
            if (msg.isNotBlank()) Text(msg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        if (list.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
                items(list) { v ->
                    val sel = v.first == s.breezeVoiceId
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sel, onClick = { onSave(s.copy(breezeVoiceId = v.first)) })
                        Column(Modifier.weight(1f)) {
                            Text(v.second, fontSize = 13.sp)
                            if (v.third.isNotBlank()) Text(v.third, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            if (!com.dshpet.android.data.KeyFactory.current().hasBreeze) { msg = "先填 Key"; return@TextButton }
                            msg = "试听中…"
                            scope.launch {
                                val r = runCatching { TtsPreview.play(breezePreviewCfg(), VoiceProfile(voiceId = v.first)) }
                                msg = r.fold({ "播放完成" }, { e -> "失败：${e.message}" })
                            }
                        }) { Text("试听", fontSize = 11.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SystemPage() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("系统 TTS", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "使用手机自带的语音引擎（各机型不同，通常不联网、延迟最低，但音色不可选、音质一般）。\n\n" +
                "当豆包 / Breeze 的 Key 未配置或请求失败时，语音对话会自动降级到它。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================================================== 小部件

@Composable
private fun Label(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(
    list: List<String>,
    selected: String,
    labelOf: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        list.forEach { v ->
            FilterChip(
                selected = v == selected,
                onClick = { onSelect(v) },
                label = { Text(labelOf(v), fontSize = 11.sp) },
            )
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("$value", fontSize = 12.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range,
            steps = steps,
        )
    }
}

// ============================================================== 数据 / 试听

/** 豆包音色表：`res/raw/doubao_voices.tsv`，格式 `voice_id \t 名字 \t 分类` */
private fun loadDoubaoVoices(ctx: android.content.Context): List<Triple<String, String, String>> =
    runCatching {
        ctx.resources.openRawResource(R.raw.doubao_voices).bufferedReader().useLines { seq ->
            seq.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size >= 2) Triple(p[0].trim(), p[1].trim(), p.getOrElse(2) { "" }.trim()) else null
            }.toList()
        }
    }.getOrElse { emptyList() }

/** 豆包试听的配置：固定 pcm（延迟最低），其余取当前设置 */
private fun doubaoPreviewCfg(s: VoiceSettings): TtsConfig = TtsConfig(
    providerId = TtsEngines.ID_VOLCENGINE,
    resourceId = s.resourceId,
    extra = mapOf(
        "format" to "pcm",
        "sample_rate" to s.sampleRate.toString(),
        "speech_rate" to s.speechRate.toString(),
        "loudness_rate" to s.loudnessRate.toString(),
        "emotion" to s.emotion,
        "emotion_scale" to s.emotionScale.toString(),
    ),
)

private fun doubaoPreviewVoice(s: VoiceSettings): VoiceProfile = VoiceProfile(
    voiceId = s.voiceId,
    emotion = s.emotion.ifBlank { null },
    emotionScale = s.emotionScale,
)

/** Breeze 试听的配置：固定 pcm / 24k / breeze-tts-2（与说话时一致） */
private fun breezePreviewCfg(): TtsConfig = TtsConfig(
    providerId = TtsEngines.ID_BREEZE,
    extra = mapOf(
        "format" to "pcm",
        "sample_rate" to "24000",
        "model_id" to "breeze-tts-2",
    ),
)

/**
 * **试听**：唯一的实现，两家只是配置不同。
 *
 * 报文交给各家 *Api、HTTP 交给 `TtsApi`，这里只留"PCM 直接写 AudioTrack"这一件事 ——
 * 原来 DoubaoPreview / BreezePreview 各抄了一份 Request.Builder + 读流 + 建轨。
 */
private object TtsPreview {

    /** 试听固定一句话，方便对比音色 */
    private const val TEXT = "你好呀，我是你的桌宠，这是当前音色的试听。"

    suspend fun play(cfg: TtsConfig, voice: VoiceProfile) = withContext(Dispatchers.IO) {
        val codec = TtsEngines.codec(cfg.providerId) ?: error("${cfg.providerId} 没有编解码器，无法试听")
        val rate = cfg.extra["sample_rate"]?.toIntOrNull() ?: 24000
        val track = newTrack(rate)
        try {
            track.play()
            TtsApi(codec, cfg).synthesize(TEXT, voice) { chunk ->
                val pcm = chunk.pcm ?: throw TtsError.Protocol("试听只支持 PCM 输出")
                track.write(pcm, 0, pcm.size)
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}

private fun newTrack(sampleRate: Int): AudioTrack = AudioTrack.Builder()
    .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    )
    .setAudioFormat(
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
    )
    .setTransferMode(AudioTrack.MODE_STREAM)
    .setBufferSizeInBytes(
        AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ) * 2
    )
    .build()
