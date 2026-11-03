package com.dshpet.android.voice

/**
 * 语音合成通用抽象层。
 *
 * 设计目标：**换平台只加一个实现类，不改上层**。
 * 上层（SpeechQueue / ReplyReader / PetOverlayService）只认这里的三样东西：
 *   1. [TtsProvider.synthesize] 流式吐音频块；
 *   2. [VoiceProfile] 音色档案（平台无关）；
 *   3. [AudioChunk] 音频块（PCM 优先，MP3 作为兼容）。
 *
 * 注意：本文件与同模块其它 voice 下的 kt 文件都是**未经编译验证**的初版代码（本机构建环境尚未打通），
 * 首次在 Android Studio 里编译时可能需要微调 import / API 签名。
 */
interface TtsProvider {

    /** 稳定标识，用于持久化选择：volcengine / openai / system ... */
    val id: String

    /** 人类可读名字，设置页展示用 */
    val displayName: String

    /**
     * 流式合成。必须在协程中调用；实现内部不要自己切线程。
     *
     * @param text 已经清洗过的单句文本（不要传多句，分句是上层的事）
     * @param voice 音色档案
     * @param onChunk 每收到一块音频回调一次（在调用协程的上下文中）
     */
    suspend fun synthesize(
        text: String,
        voice: VoiceProfile,
        onChunk: (AudioChunk) -> Unit,
    )

    /** 立即中断当前合成（用户打断、关闭窗口） */
    fun cancel()

    /** 是否配置齐全（缺 key 时上层直接降级到系统 TTS） */
    fun isConfigured(): Boolean
}

/** 平台无关的音色档案。各 provider 自己决定哪些字段生效。 */
data class VoiceProfile(
    val voiceId: String,          // 如 zh_female_sajiaoxuemei_uranus_bigtts / alloy / zh-CN-XiaoxiaoNeural
    val speed: Float = 1.0f,      // 1.0 = 原速
    val emotion: String? = null,  // happy / sad / angry / neutral ...（不支持的平台忽略）
    val emotionScale: Int = 4,    // 1..5，火山用
    val volume: Float = 1.0f,     // 1.0 = 原音量
)

/**
 * 一块音频。
 * - [pcm] 非空表示可直接喂 AudioTrack（16bit 单声道，采样率见 [sampleRate]）；
 * - [encoded] 非空表示需要解码（mp3/aac 等），交给 MediaCodec / ExoPlayer。
 */
data class AudioChunk(
    val pcm: ByteArray? = null,
    val encoded: ByteArray? = null,
    val sampleRate: Int = 24000,
    val isLast: Boolean = false,
)

/** 合成失败类别，上层据此决定降级策略 */
sealed class TtsError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : TtsError("未配置语音服务")

    /**
     * 被主动取消（用户点了停止 / 打断）。
     *
     * 单独一个类型的原因：责任链**必须**把它和"失败"区分开——失败才降级到下一家，
     * 取消则要立刻终止整条链，否则用户按了停止，系统 TTS 还会把这句话念一遍。
     */
    class Cancelled : TtsError("已取消")
    class Auth(message: String) : TtsError(message)          // 401/403 → 降级系统 TTS
    class RateLimited(message: String) : TtsError(message)   // 429 → 退避一次
    class Server(message: String) : TtsError(message)        // 5xx → 丢弃本块
    class Network(message: String, cause: Throwable? = null) : TtsError(message, cause)
    class Protocol(message: String) : TtsError(message)      // 返回体解析失败
}

/** 各 provider 的配置快照（从 PetConfig 读出来后构造） */
data class TtsConfig(
    val providerId: String,
    val baseUrl: String = "",
    val path: String = "",
    val resourceId: String = "",
    val appId: String = "",
    val extra: Map<String, String> = emptyMap(),
)
