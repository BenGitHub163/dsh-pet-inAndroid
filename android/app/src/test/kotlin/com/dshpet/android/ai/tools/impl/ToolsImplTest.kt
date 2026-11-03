package com.dshpet.android.ai.tools.impl

import android.content.Context
import com.dshpet.android.ai.tools.ToolCall
import com.dshpet.android.ai.tools.ToolContext
import com.dshpet.android.ai.tools.ToolError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两个零副作用工具的单测。
 *
 * 只测**可判定部分**：`remember` 的长度/敏感内容闸门（[RememberPolicy] 是纯函数），
 * `close_conversation` 的状态判断与"只发意图、不自己停"的契约。
 * 真正落盘的 `MemoryStore.upsert` 需要 Context，留给真机走 SPEC 的 AC-5 用例。
 */
class ToolsImplTest {

    // ------------------------------------------------ remember 的拒收规则

    @Test
    fun `太短的事实拒收`() {
        assertEquals(RememberPolicy.Rejection.TOO_SHORT, RememberPolicy.reject("嗯嗯"))
        assertEquals(RememberPolicy.Rejection.TOO_SHORT, RememberPolicy.reject("好的呀"))
    }

    @Test
    fun `普通的事实通过`() {
        assertNull(RememberPolicy.reject("我周三下午有课"))
        assertNull(RememberPolicy.reject("我不吃香菜"))
        assertNull(RememberPolicy.reject("我手机号是 13800138000"))
    }

    @Test
    fun `银行卡号被拒`() {
        assertEquals(
            RememberPolicy.Rejection.SENSITIVE,
            RememberPolicy.reject("我的银行卡号是 6222 0212 3456 7890"),
        )
        assertEquals(
            RememberPolicy.Rejection.SENSITIVE,
            RememberPolicy.reject("记住卡号 6222021234567890"),
        )
    }

    @Test
    fun `证件号被拒`() {
        assertEquals(
            RememberPolicy.Rejection.SENSITIVE,
            RememberPolicy.reject("身份证 110101199003071234"),
        )
    }

    @Test
    fun `密码与验证码被拒`() {
        assertEquals(RememberPolicy.Rejection.SENSITIVE, RememberPolicy.reject("我把密码改成 abc123 了"))
        assertEquals(RememberPolicy.Rejection.SENSITIVE, RememberPolicy.reject("收到的验证码是 1234"))
    }

    @Test
    fun `API Key 与裸长串被拒`() {
        assertEquals(RememberPolicy.Rejection.SENSITIVE, RememberPolicy.reject("api key 是 sk-abcdefghijklmnop1234"))
        assertEquals(
            RememberPolicy.Rejection.SENSITIVE,
            RememberPolicy.reject("token: abcdefghijklmnopqrstuvwxyz0123456789"),
        )
    }

    @Test
    fun `关键词兜底不会为空`() {
        val keywords = RememberPolicy.deriveKeywords("我周三下午有课")
        assertTrue(keywords.isNotEmpty())
        assertTrue(keywords.all { it.length >= 2 })
    }

    // ------------------------------------------------ close_conversation

    @Test
    fun `不在对话中时返回 not_in_voice_mode 且不停任何东西`() = runBlocking {
        val ctx = FakeToolContext(voiceActive = false)
        val result = CloseConversationTool().execute(ctx, ToolCall("c1", "close_conversation", "{}"))

        assertFalse(result.ok)
        assertEquals(ToolError.DENIED, result.error)
        assertEquals("not_in_voice_mode", JSONObject(result.content).getString("code"))
        assertTrue(ctx.closed.isEmpty())
    }

    @Test
    fun `在对话中时只发关闭意图不自己停`() = runBlocking {
        val ctx = FakeToolContext(voiceActive = true)
        val result = CloseConversationTool().execute(ctx, ToolCall("c1", "close_conversation", "{}"))

        assertTrue(result.ok)
        // 时序（先播完收尾句再 stop）由 VoiceLoop 负责，工具只发一次意图
        assertEquals(listOf("user_request"), ctx.closed)
        val content = JSONObject(result.content)
        assertTrue(content.getBoolean("ok"))
        assertFalse(content.getBoolean("already"))
    }

    // ------------------------------------------------ fake

    private class FakeToolContext(
        private val voiceActive: Boolean,
        val closed: MutableList<String> = mutableListOf(),
        val spoken: MutableList<String> = mutableListOf(),
    ) : ToolContext {
        // 这些用例不碰 Android 运行时；万一碰了就要显式失败，而不是悄悄过
        override val appContext: Context get() = error("本用例不应使用 Context")
        override val instanceId: Int = 0
        override val turnId: String = "t-unit"
        override val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
        override fun isActive(): Boolean = true
        override fun isVoiceActive(): Boolean = voiceActive
        override fun say(text: String) {
            spoken += text
        }

        override fun closeConversation(reason: String) {
            closed += reason
        }

        override fun log(tag: String, msg: String) = Unit
    }
}
