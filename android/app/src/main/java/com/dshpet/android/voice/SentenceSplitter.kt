package com.dshpet.android.voice

/**
 * 文本侧处理：把 LLM 的流式增量切成"适合合成的一句一句"。
 *
 * 为什么需要它：整段合成要等全文（3~8s），分句+预取能把首音压到 1s 内。
 * 纯 Kotlin、无 Android 依赖，可直接写单测。
 */
class SentenceSplitter(
    private val maxChunk: Int = 50,   // 单块上限（汉字），太大首音慢
    private val minChunk: Int = 6,    // 太短的句子并入下一块（"嗯。""哦。"）
) {
    private val buf = StringBuilder()

    /** 喂增量；返回"已经可以拿去合成"的完整句子（可能为空） */
    fun feed(delta: String): List<String> {
        buf.append(delta)
        val out = ArrayList<String>()
        while (true) {
            val s = buf.toString()
            val cut = findCut(s) ?: break
            val piece = s.substring(0, cut).trim()
            buf.delete(0, cut)
            if (piece.isNotEmpty()) out.add(clean(piece))
        }
        return out
    }

    /** 流结束，把尾巴全部吐出来 */
    fun flush(): List<String> {
        val rest = buf.toString().trim()
        buf.setLength(0)
        if (rest.isEmpty()) return emptyList()
        // 尾巴也可能超长，硬切
        return chunkByLength(clean(rest), maxChunk).filter { it.isNotBlank() }
    }

    fun reset() = buf.setLength(0)

    /** 找断点：优先句末标点，其次逗号/分号（且长度够），最后长度硬切 */
    private fun findCut(s: String): Int? {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c in SENTENCE_END) {
                var j = i + 1
                // 把连续的标点/引号/省略号一起吃进来
                while (j < s.length && (s[j] in SENTENCE_END || s[j] in TRAILING)) j++
                return j
            }
            i++
        }
        // 没有句末标点：只有真的很长了才在逗号处切（切得越碎，底噪/停顿越明显）
        if (s.length >= maxChunk) {
            val soft = s.lastIndexOfAny(charArrayOf('，', '；', '、', '：', ',', ';'), maxChunk)
            if (soft > minChunk) return soft + 1
            return maxChunk
        }
        val comma = s.lastIndexOfAny(charArrayOf('，', '；', '：'), s.length - 1)
        if (s.length >= maxChunk * 5 / 6 && comma > minChunk) return comma + 1
        return null
    }

    private fun chunkByLength(text: String, size: Int): List<String> {
        if (text.length <= size) return listOf(text)
        val out = ArrayList<String>()
        var p = 0
        while (p < text.length) {
            out.add(text.substring(p, minOf(p + size, text.length)))
            p += size
        }
        return out
    }

    companion object {
        private val SENTENCE_END = charArrayOf('。', '！', '？', '!', '?', '\n', '…')
        private val TRAILING = charArrayOf('"', '”', '』', '」', ')', '）', '~', '～', '。', '！', '？')

        /** 清洗：去 markdown、链接、颜文字、多余空白；代码块整段丢掉 */
        fun clean(raw: String): String {
            var s = raw
            // 代码块：整段替换成一句占位（念代码没意义）
            s = s.replace(Regex("```[\\s\\S]*?```"), "（这里有一段代码）")
            s = s.replace(Regex("`[^`]*`"), "")
            // markdown 强调/标题/列表符号
            s = s.replace(Regex("[*_#>]+"), "")
            s = s.replace(Regex("^\\s*[-+]\\s+", RegexOption.MULTILINE), "")
            // 链接：[文字](url) → 文字；裸链接 → 删
            s = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            s = s.replace(Regex("https?://\\S+"), "")
            // 颜文字/符号：常见 ASCII 组合，避免 TTS 念出来
            s = s.replace(Regex("[♪♫★☆♥♡→←↑↓※●○◆◇■□]+"), "")
            s = s.replace(Regex("\\(\\^[^)]*\\)"), "")     // (*^▽^*) 这类
            // 连续空白/空行
            s = s.replace(Regex("[ \\t]+"), " ")
            s = s.replace(Regex("\\n{2,}"), "\n")
            return s.trim()
        }

        /** 抽取 [emotion:xxx] 标记，返回 (纯文本, 情绪) */
        fun extractEmotion(raw: String): Pair<String, String?> {
            val m = Regex("\\[emotion:([a-zA-Z]+)]").find(raw) ?: return raw to null
            val text = raw.replace(m.value, "").trim()
            return text to m.groupValues[1].lowercase()
        }
    }
}
