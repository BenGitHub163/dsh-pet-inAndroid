package com.dshpet.android.api

/**
 * **豆包（火山引擎）语音接口**：地址与鉴权头在这里定义。
 * provider 只负责协议编解码（拼 JSON / 解分块），不再自己写域名。
 */
object DoubaoApi {
    const val TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
    const val HEADER_KEY = "X-Api-Key"
    const val HEADER_RESOURCE = "X-Api-Resource-Id"
}