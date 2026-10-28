package com.dshpet.android.voice.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * 读屏服务（**为视障用户设计**）——把当前屏幕上的文字**在本地朗读出来**。
 *
 * 定位很明确：这是"屏幕阅读器"能力，不是"把别人的聊天记录传上云"。
 *
 *
 * 用户要的"让桌宠知道屏幕内容"是另一个开关（`allowShareToModel`，默认关闭），
 * 打开后才会把用户**主动触发**的那一小段交给模型——而不是后台持续抓取。
 */
class PetAccessibilityService : AccessibilityService() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastAt = 0L
    private var lastText = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        enabled = true
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 300
        }
        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.CHINA
                tts?.setSpeechRate(1.05f)
            }
        }
        Log.i(TAG, "accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !enabled || !speakOnChange) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return
        if (event.packageName == packageName) return      // 自己读自己会造成回环

        val now = System.currentTimeMillis()
        if (now - lastAt < THROTTLE_MS) return

        val text = runCatching { collect(rootInActiveWindow).trim() }.getOrDefault("")
        if (text.isBlank() || text == lastText) return

        lastAt = now
        lastText = text
        lastScreenText = text
        speak(text)
    }

    /** 递归收集可见文字；密码框整棵子树直接跳过 */
    private fun collect(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null || depth > MAX_DEPTH) return ""
        if (node.isPassword) return ""                     // ← 关键：密码/验证码不读、不存
        val sb = StringBuilder()
        runCatching {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) sb.append(t).append('，')
            if (d.isNotEmpty()) sb.append(d).append('，')
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                sb.append(collect(child, depth + 1))
            }
        }
        return sb.toString()
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        val chunk = text.replace(Regex("[，,]{2,}"), "，").take(MAX_SPEAK)
        // 本地朗读：QUEUE_FLUSH 保证打断旧的，文字不离开设备
        tts?.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "pet-a11y")
    }

    override fun onInterrupt() {
        tts?.stop()
    }

    override fun onDestroy() {
        enabled = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DshVoiceA11y"
        private const val THROTTLE_MS = 2500L
        private const val MAX_DEPTH = 24
        private const val MAX_SPEAK = 300

        /** 服务是否已连接（UI 用来显示状态） */
        @Volatile
        var enabled: Boolean = false
            private set

        /** 是否在屏幕变化时自动朗读 */
        @Volatile
        var speakOnChange: Boolean = true

        /** 最近一次读到的屏幕文字（供"分享给桌宠"用；不自动上传） */
        @Volatile
        var lastScreenText: String = ""
            private set
    }
}
