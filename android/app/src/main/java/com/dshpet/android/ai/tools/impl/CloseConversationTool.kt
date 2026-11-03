package com.dshpet.android.ai.tools.impl

import com.dshpet.android.ai.tools.RiskLevel
import com.dshpet.android.ai.tools.Schema
import com.dshpet.android.ai.tools.Tool
import com.dshpet.android.ai.tools.ToolCall
import com.dshpet.android.ai.tools.ToolContext
import com.dshpet.android.ai.tools.ToolError
import com.dshpet.android.ai.tools.ToolResult
import com.dshpet.android.ai.tools.ToolSpec
import com.dshpet.android.ai.tools.toolJson

/**
 * `close_conversation`：结束当前语音对话，**桌宠留在屏幕上**。
 *
 * 三条硬约束：
 *  1. **先说完最后一句再停**：真正 `stop()` 的时序由 [ToolContext.closeConversation] 的实现方
 *     （VoiceLoop）负责 —— 本工具只发出"要关"的意图，不能自己 `stop()`（否则半句被掐，见 SPEC F6）；
 *  2. 不关浮动窗、不关语音页、不清历史与记忆（只停"这一轮对话"）；
 *  3. 幂等：重复调用不该报错，也不该重复播报。
 *
 * 待确认：SPEC §4.1 与 AC-2.4 期望"不在对话中"时返回 `ok:true, already:true`，
 * 而 AC-2.5 期望同一状态下播"这会儿没在聊呀"（对应 `not_in_voice_mode`）。
 * 两者对同一状态给了两种期望；这里先按 **失败 + not_in_voice_mode** 实现（与文案表 §6.2 一致），
 * 若最终拍板要 `already:true`，改两行即可。
 */
class CloseConversationTool : Tool {

    override val idempotent: Boolean = true

    override val spec: ToolSpec = ToolSpec(
        name = "close_conversation",
        description = "结束当前语音对话（停止倾听和说话）。仅当用户明确要求结束/关闭这次对话时调用。" +
            "用户要求关闭其他应用、或只是让你别念了之外的其他操作时不要调用。",
        parameters = Schema.obj(),
        risk = RiskLevel.LOW,
    )

    override suspend fun execute(ctx: ToolContext, call: ToolCall): ToolResult {
        val started = System.currentTimeMillis()
        if (!ctx.isVoiceActive()) {
            return ToolResult.failure(
                callId = call.id,
                error = ToolError.DENIED,
                code = "not_in_voice_mode",
                elapsedMs = System.currentTimeMillis() - started,
            )
        }
        // 交给 VoiceLoop：它会先让本轮 TTS 播完收尾句，再停监听与播报
        ctx.closeConversation("user_request")
        ctx.log(TAG, "close_conversation turn=${ctx.turnId}")
        return ToolResult.success(
            callId = call.id,
            content = toolJson("ok" to true, "already" to false),
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    private companion object {
        const val TAG = "DshToolClose"
    }
}
