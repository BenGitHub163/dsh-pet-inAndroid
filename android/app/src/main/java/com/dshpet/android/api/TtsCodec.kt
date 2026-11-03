package com.dshpet.android.api

import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.VoiceProfile

/**
 * 一次 TTS 请求的"线上形态"。
 *
 * 存在意义：把**怎么发**（URL / 头 / body / 是否流式）与**发出去**（HTTP 执行）分开。
 * codec 只产出本结构，`TtsApi` 负责真正调用 `ApiClient`，
 * 因此各个 provider 再也不需要持有 `OkHttpClient`、也不该出现域名与 `Bearer`。
 */
data class TtsHttpSpec(
    val url: String,
    val headers: Map<String, String>,
    val bodyJson: String,
    /** true = 边收边播（SSE / chunked）；false = 一次性二进制 */
    val stream: Boolean,
    /**
     * 流的**切法**：true = 按行读（SSE / NDJSON，交给 [TtsCodec.decodeLine]）；
     * false = 按字节块读（PCM / MP3 二进制，交给 [TtsCodec.decodeChunk]）。
     *
     * 火山是"JSON 行里塞 base64"，OpenAI 兼容是"裸音频字节"，两者不能用同一套读法。
     */
    val lineMode: Boolean = false,
    /**
     * 输出是不是**裸 PCM**（16bit 单声道，可直接喂 AudioTrack）。
     * false 时 `TtsApi` 会把块标成 [com.dshpet.android.voice.AudioChunk.encoded]，
     * 交给播放层解码 —— 标错会变成"把 mp3 当 pcm 播"的白噪音。
     */
    val pcm: Boolean = true,
)

/**
 * TTS **编解码器**：只描述"这家服务商的报文长什么样、流怎么切"。
 *
 * 约定（配合 `checkApiHosts` 第二条规则）：
 *  - 实现类里**不允许**出现 `Request.Builder` / `newCall(` / `toRequestBody(`；
 *  - 不允许读 `apiKey` 之外的鉴权细节（key 由 [TtsApi] 注入到 [TtsHttpSpec.headers]）；
 *  - 不许抛异常穿透到播放层：解码失败返回 null，由调用方决定丢帧还是收尾；
 *    只有**确认失败**（服务端错误码、一帧音频都没收到）才抛 [com.dshpet.android.voice.TtsError]。
 *
 * 有状态的实现（比如要统计"收到了几帧"）：在 [request] 里清空状态即可，
 * 因为一次 `synthesize` 恰好调用一次 [request]，且 provider 是按请求新建的。
 */
interface TtsCodec {

    /** 服务商标识，与 `TtsEngines.ID_*` 对齐 */
    val id: String

    /** 组装请求：文本 → 线上报文（同时重置本实例的流状态） */
    fun request(text: String, voice: VoiceProfile, cfg: TtsConfig): TtsHttpSpec

    /** 成功响应的**响应头**（key 已转小写）：给 codec 记 `x-tt-logid` 这类诊断信息 */
    fun onResponseHeaders(headers: Map<String, String>) {}

    /** 解析**行式流**（SSE / NDJSON）：返回一段可播音频或 null（控制帧/心跳） */
    fun decodeLine(line: String): ByteArray? = null

    /** 解析**二进制分块**：返回可播 PCM/编码块或 null */
    fun decodeChunk(bytes: ByteArray): ByteArray? = null

    /**
     * 流结束收尾：可以在这里抛"一帧音频都没收到"这类错误，
     * 让 `TtsChain` 有机会降级到下一家（此时还没出过声，重来是安全的）。
     */
    fun onStreamEnd() {}
}
