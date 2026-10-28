package com.dshpet.android.pet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * 桌宠动画测试台：把 91 段动画逐条列出来，点一行就让**屏幕上那只真桌宠**播这一段。
 *
 * 为什么不在这个页面里自己放一个播放器：
 * 直接指挥运行中的 [PetOverlayService] 播放，才能验证"实际效果"——
 * 包括窗口尺寸、镜像（朝左/朝右）、落点、透明度这些只在悬浮窗里才成立的细节。
 *
 * 数据来源：`assets/pet/manifest.json`（动画名 → 文件 + 时长），
 * 分类取 `file` 的一级目录（idle/turn/move/click/drag/random）。
 *
 * 用法：先让桌宠跑起来（点任意一行会自动拉起），然后回到桌面或直接在本页观察；
 * 悬浮窗永远在本页之上，所以能一边点一边看。
 */
class PetAnimTestActivity : Activity() {

    private lateinit var status: TextView

    /** manifest 读取失败时的信息（此时 status 还没建好，先存着） */
    private var manifestError: String? = null

    /** 按分类分组：分类名 → [(动画名, 时长秒)] */
    private val groups = LinkedHashMap<String, MutableList<Pair<String, Double>>>()

    /** 播放速度（0.5~2.0，保留一位小数） */
    private var speed = 1.0f
    private lateinit var speedLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "桌宠动画测试"
        loadManifest()

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        scroll.addView(root)

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF777777.toInt())
            text = manifestError ?: "点一行 → 桌宠循环播这一段。桌宠悬浮窗会盖住列表中间，长按拖到边角再点。"
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(status)

        // ---- 快捷操作 ----
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        quick.addView(button("随机一段") { play(randomName()) })
        quick.addView(button("停止循环") { clearManual() })
        quick.addView(button("启动桌宠") { ensurePet() })
        root.addView(quick)

        // ---- 播放速度（0.5×~2.0×，一位小数，实时生效）----
        root.addView(speedRow())

        // ---- 分类列表 ----
        val order = listOf("idle", "turn", "move", "click", "drag", "random")
        val cats = (order.filter { groups.containsKey(it) } + groups.keys.filter { it !in order })
        for (cat in cats) {
            val items = groups[cat] ?: continue
            root.addView(sectionHeader("$cat（${items.size} 段）"))
            for ((name, dur) in items) {
                root.addView(row(String.format("%s    %.1fs", name, dur)) { play(name) })
            }
        }

        setContentView(scroll)
    }

    // ------------------------------------------------------------------ 数据

    private fun loadManifest() {
        val text = runCatching {
            assets.open("pet/manifest.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: run {
            manifestError = "读不到 assets/pet/manifest.json"
            return
        }
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val o = root.optJSONObject(name) ?: continue
            val file = o.optString("file")
            val dur = o.optDouble("duration", 0.0)
            val cat = if (file.contains('/')) file.substringBefore('/') else "root"
            groups.getOrPut(cat) { mutableListOf() }.add(name to dur)
        }
        groups.values.forEach { it.sortBy { p -> p.first } }
    }

    private fun randomName(): String {
        val all = groups.values.flatten()
        return if (all.isEmpty()) "idle" else all.random().first
    }


    // ------------------------------------------------------------------ 速度

    /**
     * 播放速度滑杆：0.5×~2.0×，步进 0.1，标签保留**一位小数**。
     * 拖动时只发 `ACTION_PLAY_SPEED`，不重启当前动画，所以能一边看一边调。
     */
    private fun speedRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(8))

        addView(TextView(this@PetAnimTestActivity).apply {
            text = "速度"
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            width = dp(44)
        })

        val sb = android.widget.SeekBar(this@PetAnimTestActivity)
        sb.max = SPEED_STEPS
        sb.progress = speedToProgress(speed)
        sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                speed = progressToSpeed(p)
                speedLabel.text = String.format("%.1f×", speed)
                sendSpeed()
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        speedLabel = TextView(this@PetAnimTestActivity).apply {
            text = String.format("%.1f×", speed)
            textSize = 13f
            setTextColor(0xFF1565C0.toInt())
            gravity = Gravity.END
            width = dp(56)
        }
        addView(speedLabel)
    }

    private fun speedToProgress(v: Float): Int = ((v - SPEED_MIN) * 10f).toInt().coerceIn(0, SPEED_STEPS)
    private fun progressToSpeed(p: Int): Float = (SPEED_MIN + p / 10f).coerceIn(SPEED_MIN, SPEED_MAX)

    /** 只改速度，不打断当前循环 */
    private fun sendSpeed() {
        val i = Intent(this, PetOverlayService::class.java).apply {
            action = PetOverlayService.ACTION_PLAY_SPEED
            putExtra(PetOverlayService.EXTRA_SPEED, speed)
            putExtra(PetOverlayService.EXTRA_INSTANCE, 0)
        }
        runCatching { startService(i) }
    }

    // ------------------------------------------------------------------ 播放

    /** 让运行中的桌宠循环播放指定动画；服务没起来就先拉起来 */
    private fun play(name: String) {
        send(name)
        status.text = "循环播放：$name（悬浮窗在本页之上，直接看；点「停止循环」回到随机）"
    }

    /** 退出手动循环，桌宠回到自己的随机动画链 */
    private fun clearManual() {
        send("")
        status.text = "已停止循环，桌宠回到随机动画"
    }

    /** 发指令给悬浮窗服务；空字符串 = 退出循环 */
    private fun send(name: String) {
        try {
            PetOverlayService.ensureRunning(this, 0, persist = true)
        } catch (t: Throwable) {
            toast("启动桌宠失败：${t.message ?: t.javaClass.simpleName}")
            return
        }
        val i = Intent(this, PetOverlayService::class.java).apply {
            action = PetOverlayService.ACTION_PLAY
            putExtra(PetOverlayService.EXTRA_ANIM, name)
            putExtra(PetOverlayService.EXTRA_SPEED, speed)
            putExtra(PetOverlayService.EXTRA_INSTANCE, 0)
        }
        runCatching { startService(i) }.onFailure {
            toast("发送播放指令失败：${it.message}")
        }
    }

    private fun ensurePet() {
        try {
            PetOverlayService.ensureRunning(this, 0, persist = true)
            status.text = "已请求启动桌宠"
        } catch (t: Throwable) {
            toast("启动失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ 小控件

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF1565C0.toInt())
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun row(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFF222222.toInt())
        setPadding(dp(8), dp(11), dp(8), dp(11))
        isClickable = true
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF1565C0.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isClickable = true
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SPEED_MIN = 0.5f
        const val SPEED_MAX = 2.0f
        const val SPEED_STEPS = 15      // 0.5..2.0 步进 0.1 → 15 格
    }
}
