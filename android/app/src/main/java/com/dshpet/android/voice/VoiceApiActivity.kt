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
import com.dshpet.android.util.ProvideComposeHost
import com.dshpet.android.voice.core.VoiceLoop
import com.dshpet.android.voice.providers.VolcengineTtsProvider
import com.dshpet.android.voice.core.VoiceSettings
import com.dshpet.android.voice.core.VoiceSettingsStore
import com.dshpet.android.voice.core.VoiceRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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
                        Text("$id · $tag", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    if (s.doubaoKey.isBlank()) {
                        msg = "先填 Key"
                        return@OutlinedButton
                    }
                    previewing = true
                    msg = "合成中…"
                    scope.launch {
                        val r = runCatching { DoubaoPreview.play(s) }
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
    var list by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
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
        OutlinedTextField(
            value = s.breezeVoiceId,
            onValueChange = { onSave(s.copy(breezeVoiceId = it)) },
            label = { Text("音色 ID（voc_ 开头，可从下面列表选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    if (s.breezeKey.isBlank()) { msg = "先填 Key"; return@OutlinedButton }
                    loading = true; msg = "拉取中…"
                    scope.launch {
                        val r = runCatching { BreezePreview.listVoices(s.breezeKey) }
                        loading = false
                        r.onSuccess { list = it; msg = "共 ${it.size} 个音色" }
                            .onFailure { msg = "失败：${it.message}" }
                    }
                },
                enabled = !loading,
            ) { Text(if (loading) "拉取中…" else "拉取音色列表") }
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
                            Text("${v.first} · ${v.third}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            if (s.breezeKey.isBlank()) { msg = "先填 Key"; return@TextButton }
                            msg = "试听中…"
                            scope.launch {
                                val r = runCatching { BreezePreview.play(s.breezeKey, v.first) }
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

/** 豆包试听：走项目里已有的 [VolcengineTtsProvider]，PCM 直接进 AudioTrack */
private object DoubaoPreview {

    suspend fun play(s: VoiceSettings) = withContext(Dispatchers.IO) {
        val cfg = TtsConfig(
            providerId = TtsEngines.ID_VOLCENGINE,
            apiKey = s.doubaoKey,
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
        val provider = VolcengineTtsProvider(cfg)
        val track = newTrack(s.sampleRate)
        try {
            track.play()
            provider.synthesize(
                "你好呀，我是你的桌宠，这是当前音色的试听。",
                VoiceProfile(
                    voiceId = s.voiceId,
                    emotion = s.emotion.ifBlank { null },
                    emotionScale = s.emotionScale,
                ),
            ) { chunk ->
                chunk.pcm?.let { track.write(it, 0, it.size) }
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}

/** Breeze 试听 / 列表：直接用 OkHttp 打文档里的接口 */
private object BreezePreview {

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** `GET /v1/voices?language_code=zh` → (voice_id, name, language) */
    suspend fun listVoices(key: String): List<Triple<String, String, String>> = withContext(Dispatchers.IO) {
        val req = okhttp3.Request.Builder()
            .url("https://api.breeze.blue/v1/voices?language_code=zh&page_size=50")
            .addHeader("xi-api-key", key)
            .get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(120)}")
            val arr: org.json.JSONArray = org.json.JSONObject(body).optJSONArray("voices") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull<Int, Triple<String, String, String>> { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("voice_id")
                if (id.isBlank()) null
                else Triple(id, o.optString("name").ifBlank { id }, o.optString("language_code"))
            }
        }
    }

    /** `POST /v1/text-to-speech/{id}/stream?output_format=pcm` → AudioTrack（24k/mono/s16le） */
    suspend fun play(key: String, voiceId: String) = withContext(Dispatchers.IO) {
        val body = org.json.JSONObject()
            .put("text", "你好呀，我是你的桌宠，这是当前音色的试听。")
            .put("model_id", "breeze-tts-2")
            .toString()
        val req = okhttp3.Request.Builder()
            .url("https://api.breeze.blue/v1/text-to-speech/$voiceId/stream?output_format=pcm")
            .addHeader("xi-api-key", key)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code}: ${resp.body?.string()?.take(120)}")
            }
            val track = newTrack(24000)
            try {
                track.play()
                val src = resp.body!!.source()
                val buf = ByteArray(4096)
                while (!src.exhausted()) {
                    val n = src.read(buf)
                    if (n > 0) track.write(buf, 0, n)
                }
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
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
