package com.dshpet.android.voice.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.dshpet.android.voice.core.VoiceSettings
import com.dshpet.android.voice.core.VoiceSettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 正式版设置页（**唯一给用户填东西的地方**）。
 *
 * 结构：
 *   1. 语音服务（豆包 TTS Key / Resource-Id / 音色）+ 对话服务（DeepSeek Key）
 *   2. 隐私与权限（隐私档位、读屏、使用情况访问、通知使用权）
 *   3. 记忆（条数、立即整理、清空）
 *   4. 调试（语音对话、语音自测、设备能力）
 *
 * 设计原则：只做"用户可理解的开关"，不暴露内部参数；调试入口单独放在最底部。
 */
class PetSettingsActivity : Activity() {

    private lateinit var store: VoiceSettingsStore
    private var settings = VoiceSettings()

    /** 写 PetConfig 用（DataStore 是 suspend 的，不能直接在点击回调里同步写） */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    private lateinit var ridSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var privacySpinner: Spinner
    private lateinit var status: TextView

    private val resourceIds = listOf("seed-tts-2.0", "seed-tts-1.0", "seed-icl-2.0")
    /** 音色表：唯一来源见 [com.dshpet.android.voice.VoiceCatalog]（以前这里抄了一份） */
    private val voices = com.dshpet.android.voice.VoiceCatalog.VOICES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "语音与记忆设置"
        store = VoiceSettingsStore(this)
        settings = store.load()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(52, 60, 52, 48)
        }

        fun section(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setPadding(0, 26, 0, 6)
            setTextColor(0xFF1565C0.toInt())
        }

        // ---------- 1. 服务配置 ----------
        root.addView(section("① 服务配置"))
        ridSpinner = Spinner(this)
        ridSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resourceIds).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        ridSpinner.setSelection(resourceIds.indexOf(settings.resourceId).coerceAtLeast(0))
        root.addView(ridSpinner)

        voiceSpinner = Spinner(this)
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voices.map { it.second }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        voiceSpinner.setSelection(voices.indexOfFirst { it.first == settings.voiceId }.coerceAtLeast(0))
        root.addView(voiceSpinner)

        // 选完立刻落盘（不用再点"保存"）。初始化时的 setSelection 会先触发一次，
        // 用"值没变就跳过"挡掉，避免无意义写入。
        voiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val vid = voices.getOrNull(pos)?.first ?: return
                if (vid == settings.voiceId) return
                settings = settings.copy(voiceId = vid)
                persistVoiceChoice()
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        ridSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val rid = resourceIds.getOrNull(pos) ?: return
                if (rid == settings.resourceId) return
                settings = settings.copy(resourceId = rid)
                persistVoiceChoice()
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }



        // ---------- 2. 隐私与权限 ----------
        root.addView(section("③ 隐私与权限"))
        privacySpinner = Spinner(this)
        privacySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("关闭（只知道时间/电量）", "基础（知道在用哪个应用）", "详细（可主动关心）")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        privacySpinner.setSelection(settings.privacyLevel.coerceIn(0, 2))
        root.addView(privacySpinner)

        root.addView(rowOf("读屏（无障碍）", "为视障用户本地朗读屏幕文字，密码框自动跳过") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(rowOf("使用情况访问", "让桌宠知道你正在用什么应用、今天用了多久") {
            startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        root.addView(rowOf("通知使用权", "只用于读取『正在播放什么』，不读消息内容") {
            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        // ---------- 3. 记忆 ----------
        root.addView(section("④ 记忆"))
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 4, 0, 6)
        }
        root.addView(status)
        root.addView(rowOf("清空全部记忆", "删除长期事实与话题记忆（不可恢复）") {
            val s = VoiceSettingsStore(applicationContext)
            s.load()
            com.dshpet.android.voice.memory.MemoryStore(applicationContext).clear()
            com.dshpet.android.voice.memory.SegmentStore(applicationContext).clear()
            refreshStatus()
        })

        // ---------- 4. 调试 ----------
        root.addView(section("⑤ 调试（内部使用）"))
        // 「强制启动桌宠」「语音对话测试台」「TTS 试听台」已统一搬到主页面的「测试」标签页，
        // 这里只保留一个指路说明，避免三处入口各写一份。
        root.addView(rowOf("测试工具已移走", "强制启动桌宠 / 语音对话测试台 / TTS 试听台 / 桌宠动画测试 → 主界面最右侧「测试」") {
            android.widget.Toast.makeText(this, "回到主界面，点最右边的「测试」", android.widget.Toast.LENGTH_SHORT).show()
        })
        root.addView(rowOf("用户日志", "最近 50 条：桌宠启动、语音、记忆等事件（可复制反馈）") {
            val text = com.dshpet.android.util.AppLog.readUserLog(50)
            android.app.AlertDialog.Builder(this)
                .setTitle("用户日志")
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("pet-log", text))
                    android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                }
                .show()
        })

        root.addView(rowOf("系统设置里的权限说明", "查看各权限用途") {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName")))
        })

        // ---------- 保存 ----------
        root.addView(Button(this).apply {
            text = "保存"
            setPadding(0, 24, 0, 8)
            setOnClickListener { save() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private fun rowOf(title: String, desc: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 14, 0, 8)
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFF1565C0.toInt())
        })
        row.addView(TextView(this).apply {
            text = desc
            textSize = 11f
            setTextColor(0xFF777777.toInt())
        })
        return row
    }

    private fun save() {
        settings = settings.copy(
            resourceId = resourceIds[ridSpinner.selectedItemPosition],
            voiceId = voices[voiceSpinner.selectedItemPosition].first,
            privacyLevel = privacySpinner.selectedItemPosition,
        )
        persistVoiceChoice()
        android.widget.Toast.makeText(this, "已保存", android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 把音色/资源落盘：`VoiceSettingsStore`（缓存）+ `PetConfig`（**权威源**）。
     *
     * 为什么必须两处都写：`VoiceRuntime.buildSettings()` 会用 PetConfig 覆盖 VoiceSettings，
     * 只写后者等于白改（这就是"音色改不了"的根因）。
     * 另外，运行中的 VoiceLoop 不会自动换音色（`buildTts()` 只在 start 时装配一次），
     * 所以这里顺手把它停掉，下次开口就是新音色。
     */
    private fun persistVoiceChoice() {
        store.save(settings)
        scope.launch {
            val cfg = com.dshpet.android.data.PetConfig.get(applicationContext)
            cfg.setDoubaoResourceId(settings.resourceId)
            cfg.setDoubaoVoiceId(settings.voiceId)
            com.dshpet.android.util.AppLog.u("语音", "音色/资源已写入 PetConfig: ${settings.voiceId} / ${settings.resourceId}")
            if (com.dshpet.android.voice.core.VoiceLoop.running) {
                com.dshpet.android.voice.core.VoiceRuntime.stop()
            }
            status.text = "已保存 ✓（音色 ${settings.voiceId.takeLast(12)}；下次开口生效）"
        }
    }

    private fun refreshStatus() {
        val facts = com.dshpet.android.voice.memory.MemoryStore(applicationContext).size()
        val segs = com.dshpet.android.voice.memory.SegmentStore(applicationContext).size()
        status.text = "当前记忆：$facts 条事实 / $segs 条话题"
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
