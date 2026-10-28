package com.dshpet.android.pet

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dshpet.android.data.PetConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 「自言自语台词」管理页（从 外观 → 自言自语 进来）。
 *
 * 交互按用户要求做成**逐条**的：
 *  - 固定 [PetConfig.MAX_SELF_TALK_LINES] 个输入格；
 *  - 每一行右边有「存」和「删」：存 = 把这一条写进配置，删 = 清掉这一条（同样立刻落盘）；
 *  - 空行会被忽略，所以"删掉中间一条"不会留下空洞。
 *
 * 存储：`PetConfig.selfTalkLines()`（`self_talk_lines`，一行一条、保序），
 * 写入时同步更新老键 `self_talk_texts`（服务侧监听的是它）。
 *
 * 用 View 而不是 Compose：这个页面只有输入框和按钮，View 版启动更快、IME 行为更稳。
 */
class SelfTalkTextsActivity : Activity() {

    private lateinit var cfg: PetConfig
    private val scope = CoroutineScope(Dispatchers.Main)

    /** 15 个输入框（下标 = 槽位） */
    private val fields = ArrayList<EditText>()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "自言自语台词"
        cfg = PetConfig.get(this)

        val dp = resources.displayMetrics.density

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        root.addView(
            TextView(this).apply {
                text = "一行一句，最多 ${PetConfig.MAX_SELF_TALK_LINES} 条。改完点右边的「存」；「删」会立刻把这一条删掉。\n" +
                        "留空的格子会被忽略，桌宠说话时从这些句子里随机挑。"
                textSize = 12f
                setTextColor(Color.parseColor("#666666"))
                setLineSpacing(0f, 1.15f)
            },
        )

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#3F51B5"))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        root.addView(status)

        // ---- 15 个槽位 ----
        repeat(PetConfig.MAX_SELF_TALK_LINES) { index ->
            root.addView(buildRow(index, dp))
        }

        // ---- 底部：全部保存 ----
        root.addView(
            Button(this).apply {
                text = "全部保存"
                setOnClickListener {
                    saveAll(then = { Toast.makeText(this@SelfTalkTextsActivity, "已保存", Toast.LENGTH_SHORT).show() })
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (12 * dp).toInt() },
        )

        setContentView(scroll)
        loadLines()
    }

    /** 一行：序号 + 输入框 + 存 / 删 */
    private fun buildRow(index: Int, dp: Float): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        row.addView(
            TextView(this).apply {
                text = "${index + 1}"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#888888"))
                setTypeface(typeface, Typeface.NORMAL)
            },
            LinearLayout.LayoutParams((22 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val input = EditText(this).apply {
            hint = "第 ${index + 1} 条"
            textSize = 14f
            maxLines = 2
            filters = arrayOf(InputFilter.LengthFilter(40))
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        }
        row.addView(
            input,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        fields.add(input)

        val saveBtn = Button(this).apply {
            text = "存"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            setOnClickListener {
                saveAll(then = {
                    Toast.makeText(
                        this@SelfTalkTextsActivity,
                        if (input.text.isBlank()) "第 ${index + 1} 条是空的，已忽略" else "第 ${index + 1} 条已保存",
                        Toast.LENGTH_SHORT,
                    ).show()
                })
            }
        }
        row.addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val delBtn = Button(this).apply {
            text = "删"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            setOnClickListener {
                input.setText("")
                saveAll(then = {
                    Toast.makeText(this@SelfTalkTextsActivity, "第 ${index + 1} 条已删除", Toast.LENGTH_SHORT).show()
                })
            }
        }
        row.addView(delBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    /** 读配置 → 填到 15 个格子里 */
    private fun loadLines() {
        scope.launch {
            runCatching { cfg.selfTalkLines() }
                .onSuccess { lines ->
                    fields.forEachIndexed { i, f -> f.setText(lines.getOrElse(i) { "" }) }
                    status.text = "当前 ${lines.size} 条"
                }
                .onFailure { status.text = "读取失败：${it.message}" }
        }
    }

    /**
     * 把 15 个格子里的非空内容按顺序落盘。
     * 空行直接丢掉 —— 所以"删"就是清空那一格 + 保存。
     */
    private fun saveAll(then: () -> Unit = {}) {
        val lines = fields.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
        scope.launch {
            runCatching { cfg.setSelfTalkLines(lines) }
                .onSuccess {
                    status.text = "当前 ${lines.size} 条（已保存）"
                    then()
                }
                .onFailure {
                    status.text = "保存失败：${it.message}"
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 离开页面时兜底存一次：用户可能改完直接按返回
        runCatching {
            val lines = fields.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
            scope.launch { runCatching { cfg.setSelfTalkLines(lines) } }
        }
    }
}
