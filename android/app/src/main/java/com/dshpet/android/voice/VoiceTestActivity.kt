package com.dshpet.android.voice

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.dshpet.android.R
import com.dshpet.android.voice.providers.VolcengineTtsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 豆包语音合成 —— 可视化调试/试听界面（开发期用）。
 *
 * 覆盖了官方文档里会影响的全部常用选项：
 *   API Key / X-Api-Resource-Id / 请求体形态 / 音色（443 个，可筛选）/ 情绪 / 情绪强度 /
 *   语速 / 音量 / 输出格式 / 采样率 / 文本
 *
 * 设置会存 SharedPreferences，下次打开自动回填；点「试听」立即流式合成并播放。
 */
class VoiceTestActivity : Activity() {

    private data class Voice(val id: String, val name: String, val tag: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { getSharedPreferences("voice_test", Context.MODE_PRIVATE) }

    private var voices: List<Voice> = emptyList()
    private var selected: Voice? = null

    private lateinit var status: TextView
    private lateinit var keyBox: EditText
    private lateinit var textBox: EditText
    private lateinit var voiceLabel: TextView

    private lateinit var ridSpinner: Spinner
    private lateinit var bodySpinner: Spinner
    private lateinit var breezeKeyBox: EditText
    private lateinit var breezeVoiceBox: Spinner
    private var breezeVoices: List<Pair<String, String>> = emptyList()
    private lateinit var emotionSpinner: Spinner
    private lateinit var formatSpinner: Spinner
    private lateinit var rateSpinner: Spinner

    private var speechRate = 0
    private var loudnessRate = 0
    private var emotionScale = 4

    private val resourceIds = listOf("seed-tts-2.0", "seed-tts-1.0", "seed-icl-2.0", "seed-icl-1.0")
    private val bodyStyles = listOf("nested（官方文档）", "flat（简化）")
    private val emotions = listOf("无", "happy", "sad", "angry", "surprised", "fear", "hate", "excited", "neutral")
    private val formats = listOf("pcm", "mp3")
    private val sampleRates = listOf("16000", "24000", "48000")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "语音自测 · 豆包"
        voices = loadVoices()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }

        fun section(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setPadding(0, 28, 0, 6)
            setTextColor(0xFF666666.toInt())
        }

        // ---------- 凭据 ----------
        root.addView(section("API Key（火山引擎控制台创建，ak- 开头）"))
        keyBox = EditText(this).apply {
            hint = "ak-..."
            // 本页 prefs 没存过 → 回落到"豆包语音"的正式 Key（设置页/语音设置写入的那个），
            // 这样试音台一打开就是已配置状态，不用重填
            val saved = prefs.getString("apiKey", "").orEmpty()
            setText(
                saved.ifBlank {
                    runCatching {
                        com.dshpet.android.voice.core.VoiceSettingsStore(this@VoiceTestActivity).load().doubaoKey
                    }.getOrDefault("")
                }
            )
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(keyBox)

        root.addView(section("X-Api-Resource-Id（决定用 1.0/2.0/复刻，也决定计费商品）"))
        ridSpinner = Spinner(this)
        ridSpinner.adapter = simple(resourceIds)
        root.addView(ridSpinner)

        root.addView(section("请求体形态（400 时可切换试试）"))
        bodySpinner = Spinner(this)
        bodySpinner.adapter = simple(bodyStyles)
        root.addView(bodySpinner)

        // ---------- 第二家服务商：Breeze Blue ----------
        root.addView(section("Breeze Blue · API Key（brz_ 开头，本页独立配置）"))
        breezeKeyBox = EditText(this).apply {
            hint = "brz_..."
            setText(
                prefs.getString("breezeKey", "").orEmpty().ifBlank {
                    runCatching {
                        com.dshpet.android.voice.core.VoiceSettingsStore
                            .get(this@VoiceTestActivity).load().breezeKey
                    }.getOrDefault("")
                }
            )
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(breezeKeyBox)

        root.addView(section("Breeze Blue · 音色（按名字选）"))
        breezeVoiceBox = Spinner(this)
        root.addView(breezeVoiceBox)
        root.addView(Button(this).apply {
            text = "刷新 Breeze 音色列表"
            setOnClickListener { refreshBreezeVoices() }
        })

        // ---------- 音色 ----------
        root.addView(section("音色（共 ${voices.size} 个，点右侧按钮筛选）"))
        val voiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        voiceLabel = TextView(this).apply {
            text = "未选择"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 12, 0, 0)
        }
        voiceRow.addView(voiceLabel)
        voiceRow.addView(Button(this).apply {
            text = "选择音色"
            setOnClickListener { showVoicePicker() }
        })
        root.addView(voiceRow)

        // ---------- 情绪 ----------
        root.addView(section("情绪（部分音色不支持，400 时改成「无」）"))
        emotionSpinner = Spinner(this)
        emotionSpinner.adapter = simple(emotions)
        root.addView(emotionSpinner)

        root.addView(section("情绪强度 emotion_scale（1~5，非线性）"))
        root.addView(seek(1, 5, emotionScale) { emotionScale = it })

        // ---------- 语速 / 音量 ----------
        root.addView(section("语速 speech_rate（-50=0.5倍 … 0=正常 … 100=2倍）"))
        root.addView(seek(0, 150, speechRate + 50) { speechRate = it - 50 })
        root.addView(section("音量 loudness_rate（-50 … 100）"))
        root.addView(seek(0, 150, loudnessRate + 50) { loudnessRate = it - 50 })

        // ---------- 输出格式 ----------
        root.addView(section("输出格式 / 采样率（pcm 最快；mp3 体积小但要解码）"))
        formatSpinner = Spinner(this)
        formatSpinner.adapter = simple(formats)
        root.addView(formatSpinner)
        rateSpinner = Spinner(this)
        rateSpinner.adapter = simple(sampleRates)
        rateSpinner.setSelection(1)
        root.addView(rateSpinner)

        // ---------- 文本 ----------
        root.addView(section("要朗读的文本"))
        textBox = EditText(this).apply {
            setText(prefs.getString("text", "今天也要认真工作呀。"))
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(textBox)

        // ---------- 按钮 ----------
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 8)
        }
        row.addView(Button(this).apply {
            text = "试听豆包"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { speak(TtsEngines.ID_VOLCENGINE) }
        })
        row.addView(Button(this).apply {
            text = "试听Breeze"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { speak(TtsEngines.ID_BREEZE) }
        })
        row.addView(Button(this).apply {
            text = "系统TTS"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { speak(TtsEngines.ID_SYSTEM) }
        })
        row.addView(Button(this).apply {
            text = "保存"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { savePrefs(); ui("已保存设置") }
        })
        root.addView(row)

        status = TextView(this).apply {
            text = "填好 API Key 和音色，点「试听豆包」。日志：adb logcat -s DshVoice"
            textSize = 12f
            setPadding(0, 12, 0, 0)
        }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
        renderBreezeVoices()

        // 回填上次
        selected = voices.firstOrNull { it.id == prefs.getString("voiceId", "") }
            ?: voices.firstOrNull { it.id.startsWith("zh_female_sajiaoxuemei") }
            ?: voices.firstOrNull()
        renderVoice()
        ridSpinner.setSelection(resourceIds.indexOf(prefs.getString("resourceId", "seed-tts-2.0")).coerceAtLeast(0))

        // 自动测试：am start ... --es autotest system|volcengine  --es apiKey ak-xxx
        intent?.getStringExtra("apiKey")?.let { if (it.isNotBlank()) keyBox.setText(it) }
        intent?.getStringExtra("voiceId")?.let { vid ->
            voices.firstOrNull { it.id == vid }?.let { selected = it; renderVoice() }
        }
        when (intent?.getStringExtra("autotest")) {
            "system" -> window.decorView.postDelayed({ speak(TtsEngines.ID_SYSTEM) }, 800)
            "volcengine" -> window.decorView.postDelayed({ speak(TtsEngines.ID_VOLCENGINE) }, 800)
        }
    }

    // ---------------- 音色选择 ----------------

    private fun showVoicePicker() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }
        val filter = EditText(this).apply { hint = "搜名字或 voice_id（如 学妹 / 萝莉 / uranus）" }
        box.addView(filter)

        val tagSpinner = Spinner(this)
        val tags = listOf("全部", "二次元", "女声", "男声", "童声")
        tagSpinner.adapter = simple(tags)
        box.addView(tagSpinner)

        val listView = ListView(this)
        box.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 900))

        var current = voices
        fun render() {
            val q = filter.text.toString().trim()
            val tag = tags[tagSpinner.selectedItemPosition]
            current = voices.filter { v ->
                (tag == "全部" || v.tag.contains(tag)) &&
                    (q.isEmpty() || v.name.contains(q, true) || v.id.contains(q, true))
            }
            listView.adapter = ArrayAdapter(
                this, android.R.layout.simple_list_item_2, android.R.id.text1,
                current.map { "${it.name}   ${if (it.tag != "其他") "· " + it.tag else ""}" }
            ).apply {
                // 第二行显示 voice_id
            }
            listView.adapter = object : ArrayAdapter<Voice>(
                this@VoiceTestActivity, android.R.layout.simple_list_item_2, android.R.id.text1, current
            ) {
                override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                    val v = super.getView(position, convertView, parent) as android.widget.TwoLineListItem
                    v.text1?.text = current[position].name
                    v.text2?.text = current[position].id
                    return v
                }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择音色（${voices.size}）")
            .setView(box)
            .setNegativeButton("取消", null)
            .create()

        filter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        tagSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = render()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            selected = current[pos]
            renderVoice()
            dialog.dismiss()
        }
        render()
        dialog.show()
    }

    private fun renderVoice() {
        val v = selected
        voiceLabel.text = if (v == null) "未选择" else "${v.name}（${v.tag}）\n${v.id}"
    }

    private fun loadVoices(): List<Voice> {
        return try {
            resources.openRawResource(R.raw.doubao_voices).bufferedReader().useLines { seq ->
                seq.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size >= 2) Voice(p[0], p[1], p.getOrElse(2) { "其他" }) else null
                }.toList()
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    // ---------------- 试听 ----------------

    /** 用缓存填充 Breeze 音色下拉（只显示名字） */
    private fun renderBreezeVoices() {
        breezeVoices = com.dshpet.android.voice.BreezeVoices.cached(this)
        breezeVoiceBox.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            breezeVoices.map { it.second }.ifEmpty { listOf("（点下面按钮刷新音色列表）") },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val want = prefs.getString("breezeVoiceId", "").orEmpty().ifBlank {
            runCatching {
                com.dshpet.android.voice.core.VoiceSettingsStore.get(this).load().breezeVoiceId
            }.getOrDefault("")
        }
        breezeVoiceBox.setSelection(breezeVoices.indexOfFirst { it.first == want }.coerceAtLeast(0))
        breezeVoiceBox.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = Unit
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun refreshBreezeVoices() {
        val key = breezeKeyBox.text.toString().trim()
        if (key.isBlank()) return ui("先填 Breeze Key")
        scope.launch {
            runCatching { com.dshpet.android.voice.BreezeVoices.fetch(applicationContext, key) }
                .onSuccess { runOnUiThread { renderBreezeVoices(); ui("已刷新 " + it.size + " 个音色") } }
                .onFailure { runOnUiThread { ui("拉取失败：" + (it.message ?: "网络错误")) } }
        }
    }

    private fun speak(providerId: String) {
        val text = textBox.text.toString().ifBlank { "今天也要认真工作呀。" }
        // 两家服务商各自的配置（本页独立于「语音 API」页，专门用来试参数）
        val isBreeze = providerId == TtsEngines.ID_BREEZE
        val voice = if (isBreeze) null else (selected ?: return ui("先选一个音色"))
        val breezeVoiceId = breezeVoices.getOrNull(breezeVoiceBox.selectedItemPosition)?.first.orEmpty()
        if (isBreeze && breezeVoiceId.isBlank()) return ui("先填 Breeze 音色 ID（voc_ 开头）")
        val cfg = if (isBreeze) TtsConfig(
            providerId = TtsEngines.ID_BREEZE,
            apiKey = breezeKeyBox.text.toString().trim(),
            extra = mapOf(
                "format" to "pcm",
                "sample_rate" to "24000",
                "model_id" to "breeze-tts-2",
            ),
        ) else TtsConfig(
            providerId = providerId,
            apiKey = keyBox.text.toString().trim(),
            resourceId = resourceIds[ridSpinner.selectedItemPosition],
            extra = mapOf(
                "format" to formats[formatSpinner.selectedItemPosition],
                "sample_rate" to sampleRates[rateSpinner.selectedItemPosition],
                "speech_rate" to speechRate.toString(),
                "loudness_rate" to loudnessRate.toString(),
                "emotion" to if (emotionSpinner.selectedItemPosition == 0) "" else emotions[emotionSpinner.selectedItemPosition],
                "emotion_scale" to emotionScale.toString(),
                "body_style" to if (bodySpinner.selectedItemPosition == 0) "nested" else "flat",
            ),
        )
        val profile = if (isBreeze) VoiceProfile(voiceId = breezeVoiceId) else VoiceProfile(voiceId = voice!!.id)
        val sink = AudioSink(applicationContext)
        ui("合成中…\nprovider=" + providerId + "  voice=" + (if (isBreeze) breezeVoiceId else voice!!.name))
        savePrefs()

        scope.launch {
            val t0 = System.currentTimeMillis()
            var pcm = 0
            var first = 0L
            var chunks = 0
            var provider: TtsProvider? = null
            try {
                provider = TtsEngines.create(applicationContext, cfg)
                provider.synthesize(text, profile) { chunk ->
                    chunks++
                    chunk.pcm?.let { b ->
                        if (pcm == 0) {
                            sink.begin(chunk.sampleRate)
                            first = System.currentTimeMillis() - t0
                        }
                        sink.writePcm(b)
                        pcm += b.size
                    }
                    chunk.encoded?.let { e ->
                        if (first == 0L) first = System.currentTimeMillis() - t0
                        sink.playEncoded(e, if (cfg.extra["format"] == "mp3") "mp3" else "wav")
                    }
                }
                if (pcm > 0) Thread.sleep(AudioSink.pcmDurationMs(pcm, 24000).coerceAtMost(20000))
                else sink.awaitDone()
                val total = System.currentTimeMillis() - t0
                ui("✅ 成功\n首包 ${first}ms ｜ 总耗时 ${total}ms\n分块 $chunks ｜ PCM $pcm 字节（约 ${pcm / 48000.0}s）")
            } catch (e: Throwable) {
                ui("❌ ${e.javaClass.simpleName}\n${e.message}")
            } finally {
                sink.release()
                (provider as? com.dshpet.android.voice.providers.SystemTtsProvider)?.release()
            }
        }
    }

    // ---------------- 小工具 ----------------

    private fun simple(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun seek(min: Int, max: Int, initial: Int, onChange: (Int) -> Unit): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            text = initial.toString()
            textSize = 12f
        }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (initial - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    label.text = v.toString()
                    onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        onChange(initial)
        wrap.addView(bar)
        wrap.addView(label)
        return wrap
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("apiKey", keyBox.text.toString().trim())
            .putString("voiceId", selected?.id ?: "")
            .putString("breezeKey", runCatching { breezeKeyBox.text.toString().trim() }.getOrDefault(""))
            .putString(
                "breezeVoiceId",
                runCatching { breezeVoices.getOrNull(breezeVoiceBox.selectedItemPosition)?.first.orEmpty() }
                    .getOrDefault(""),
            )
            .putString("resourceId", resourceIds[ridSpinner.selectedItemPosition])
            .putString("text", textBox.text.toString())
            .apply()
    }

    private fun ui(msg: String) = runOnUiThread { status.text = msg }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
