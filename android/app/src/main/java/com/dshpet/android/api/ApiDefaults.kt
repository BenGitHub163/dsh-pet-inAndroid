package com.dshpet.android.api

/**
 * 对话接口的默认值（**全仓唯一一份**）。
 *
 * 之前这些字面量出现在 PetConfig、ChatConfigFactory、MainActivity、VoiceLoopActivity
 * 四处，改一处忘三处。现在统一放这里：
 *  - 配置层（PetConfig）用它做默认值；
 *  - UI（MainActivity）用它做输入框默认值；
 *  - 接口层（ChatApi）用它做兜底。
 *
 * 只放"默认值"：不含 key、不发请求。
 */
object ApiDefaults {
    const val CHAT_BASE_URL = "https://api.deepseek.com"
    const val CHAT_PATH = "/v1/chat/completions"
    const val CHAT_MODEL = "deepseek-flash"
}