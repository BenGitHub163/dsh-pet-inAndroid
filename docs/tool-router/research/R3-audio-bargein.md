# R3 多发声源 + 语音打断（barge-in）调研

> 调研基线 2026-11-03。标注口径：**已核实** = 官方文档原文 / AOSP 源码 / 公开可复现实测；**文档说明** = 仅文档写明、本次未实测；**待确认** = 无公开依据，须真机自测。
> 目标：给 `SpeechGate` 找现成参考，不自己造轮子。

## 0. 结论先行

1. **AudioFocus 管不了 App 内部互抢。** 它只在"你的 App"与"别的 App"之间仲裁；没有 MediaSession 时同一个包名共用一个 clientId，5–6 个发声入口在系统眼里是**同一个焦点持有者**（`待确认`：各 OEM 的 collapse 细节）。内部谁让谁只能自己算——`SpeechGate` 的存在理由成立。
2. **但 Focus 不该留在各自的 `SpeechPlayer` 里**（DESIGN §5.2 的现状）：同一 clientId 下，任一处 `abandonAudioFocusRequest()` 会把整个 App 摘出焦点栈。应收归 `SpeechGate` 单点持有。
3. **"说完最后一句"不是把停止变慢。** Pipecat / LiveKit 的做法是：判定层先决定"要不要打断"，一旦决定，执行层**永远立即丢弃未播音频**。要"说完这句"= 句级软停（停投喂 + 等 drain），不是延迟 abort。
4. **真 barge-in（边播边听）本期不值得做。** Android 上 AEC 不保证可用，`isAvailable()` 报 true 但实际不消回声的机型有公开报告；成本 2–4 人日 + 逐机型调参。用"点击打断 + 播完当前句"覆盖 90% 体感。
5. 现工程两处必须改：`playEncodedBlocking`（MediaPlayer + `Thread.sleep(40)` 轮询，外部打不断）、`requestFocus()` 的 `focusRequest != null` 早退（一次 LOSS 后永不重新申请）。

## 1. AudioFocus 速查（Android 8+ / `AudioFocusRequest`）

| 场景 | focusGain | AudioAttributes | 回调处理 |
|---|---|---|---|
| 语音对话流式 TTS（可能 >1min） | `AUDIOFOCUS_GAIN_TRANSIENT` | `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH` | `LOSS`→abort+释放；`LOSS_TRANSIENT`→**pause（不 flush）**，`GAIN`→续播；`CAN_DUCK`→按 0.3 继续（人声不该被压着说） |
| 自说自话 / 试音台 / 测试台（<30s） | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` | 同上 | `LOSS`/`LOSS_TRANSIENT`→直接结束；`CAN_DUCK`→忽略 |
| 桌宠音效（<1s，非语音） | 不申请 | `USAGE_GAME` + `CONTENT_TYPE_SONIFICATION` | 无 |
| 无障碍读屏（系统 TTS） | 不申请（引擎自己申请） | 引擎决定，常见 `USAGE_ASSISTANCE_ACCESSIBILITY` | 只能在 `speak()` 前抑制（现状已如此） |
| 被别人压时 | 加 `setWillPauseWhenDucked(true)` | — | 宁可暂停，也不要被压低继续说 |

**四条硬事实**

- `AUDIOFOCUS_LOSS_TRANSIENT` 该**停还是降**：官方语义是"短暂让路，之后可恢复"→ `pause()` 保数据；只有 `LOSS_TRANSIENT_CAN_DUCK` 才是"可以压低音量继续"。（[焦点指南](https://developer.android.com/media/optimize/audio-focus)）
- **多路共存（混音）没有"允许混音"这种标志**。要混音只有两条路：① 自己内部混成一个 `AudioTrack`；② 对别人用 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`（对方收到 `CAN_DUCK`，自行压音量而不是停）。[AudioManager](https://developer.android.com/reference/android/media/AudioManager)
- `requestAudioFocus()` 返回 `GRANTED / FAILED / DELAYED`，`FAILED` 就别播；要延迟获得需 `setAcceptsDelayedFocusGain(true)`。[AudioFocusRequest](https://developer.android.com/reference/android/media/AudioFocusRequest)
- **`LOSS` 之后必须重新 request 才能恢复**。缓存 `focusRequest` 后早退 = 恢复播放时全程无焦点（现 `SpeechPlayer.kt:214-216` 就是这个写法）。

```kotlin
// 单点持有：整个 App 只有这一个 FocusOwner
object FocusOwner {
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val handler = Handler(Looper.getMainLooper())
    private val listener = AudioManager.OnAudioFocusChangeListener { change -> /* 分发给 SpeechGate */ }
    // gain 类型不可改，缓存两份
    private val reqDuck = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(listener, handler).build()
    private val reqShort = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attrs).setOnAudioFocusChangeListener(listener, handler).build()
}
```

`abandon` 只在"确定不再播"时调，别在每次 pause 时调（否则别的 App 的音乐会反复起落）。

## 2. AudioTrack / MediaPlayer 的打断语义（已核实，官方原文）

| 调用 | 多快静音 | 已写未播的数据 | 用途 |
|---|---|---|---|
| `pause()` | 近立即；硬件缓冲里还有 ~10–40ms 会放完，官方**未承诺**立即 | 保留，`play()` 可续播 | 临时让路 |
| `pause()` + `flush()` | 同上，之后无残留 | 丢弃 | **abort / barge-in 的正确姿势**（现工程已这么做） |
| `stop()` | **不立即**：MODE_STREAM 下"audio will stop playing after the last buffer that was written has been played" | 放完 | 优雅停止；之后再 `flush()` 才能复用 track |
| `setVolume(0f)` | 亚帧级（mixer 增益） | 保留 | 先哑再停，省 10–30ms；淡出靠 ramp |
| `setPlaybackParams/setPlaybackRate` | — | — | **变速=重采样、必然变调**（`setPlaybackRate` 原文："negative pitch shift"）；别拿它当暂停 |
| `MediaPlayer.stop()` | 异步、不保证即时，之后要重新 `prepare()` | 丢弃 | 不适合流式 TTS |
| `SoundPool.stop(streamId)` | 立即 | — | 短音效首选，多路天然混音 |

**优雅停止：业界三种做法**

1. **句级软停（推荐）**：立刻停掉 SSE/LLM 与"新句子入队"，当前句 PCM 继续写完，用 `playbackHeadPosition` 等到播完再释放租约。用户听到"这句说完就没了"。
2. **drain / EOS 标记**：往播放队列写一个结束标记，播放线程读到就退出。等价于 1，前提是队列里只有完整句子。
3. **硬停**：`setVolume(0f)` → `pause()` → `flush()`，用于真 barge-in / 关闭。

关键：**不要 cancel 写协程**。cancel 之后既不知道播到哪，也拿不到精确结束点；用"停止投喂 + 等 drain + 3s 兜底"替代。现 `stop()` 同时 cancel 协程 + abort player，正是"半句被掐"的根因。

⚠️ `write()` 官方注明"thread safe with respect to `stop()` calls, in which case all of the specified data might not be **written**"——写循环必须校验返回值与 session token，否则打断后还在往已 flush 的 track 写，抛 `ERROR_INVALID_OPERATION` 或串音。

## 3. barge-in 三种实现路径

| 路径 | 做法 | 成本 | 可靠性 | 机型差异 |
|---|---|---|---|---|
| A 手势/按钮打断 | 点桌宠/悬浮球；顺带执行"播完当前句" | 0（已做） | 100% | 无 |
| B 半双工 | 播 TTS 时不开麦，播完再听 | 低 | 100%，但不是 barge-in | 无 |
| C 自建 AEC + VAD | `AudioRecord(VOICE_COMMUNICATION)` + `AcousticEchoCanceler` + 能量/模型 VAD，连续语音 ≥200–300ms 才触发 | 中（2–4 人日 + 调参） | 外放 60–80%；音量 >70% 或嘈杂环境误触 | **大**：三星等机型 `isAvailable()==true` 但 AEC 实际无效（[SO 46560814](https://stackoverflow.com/questions/46560814/acousticechocanceler-on-samsung-devices-not-working)） |
| D `SpeechRecognizer` 常开 | 播放期间维持识别会话 | 高 | 低 | 大 |

**为什么 D 不可行**

- 它是一次一句话的会话，结束后要重启，**空窗 300ms+**，"闭嘴"延迟不可控。
- 麦克风与预处理在识别引擎进程里，**你挂不上 AEC，也拿不到 PCM 做门限**；能不能消掉自己的 TTS 全看引擎（Google / 厂商）实现。
- 识别结果要等出词（数百 ms–1s），而静音要求 50ms 级 → 手感是"先说完半句再停"。
- 真机上还有 `ERROR_RECOGNIZER_BUSY` / `ERROR_CLIENT`；别的 App 占麦时拿到静音而非报错（见 [共享音频输入](https://developer.android.com/media/platform/sharing-audio-input)，细节 `待确认`）。
- AEC 本身也不是强保证：AOSP 只说 `VOICE_COMMUNICATION` 采集"**should**"提供 AEC（[pre-processing](https://source.android.com/docs/core/audio/implement-pre-processing)），`AcousticEchoCanceler.create()` 设备不实现就返回 null。

**框架思路（只要阈值与结构）**

- **Pipecat**：打断是一个 `InterruptionFrame` 系统帧，一次广播做完四件事——取消 LLM 流、TTS 清缓冲、transport 丢弃未播音频、"已说出的文本"提交进 context。防误触用 `MinWordsUserTurnStartStrategy(min_words=3)`；要"别打断我"用 mute 策略（`AlwaysUserMuteStrategy`），**不是**去改停止逻辑。[Interruptions](https://docs.pipecat.ai/pipecat/fundamentals/interruptions)
- **LiveKit**：`interruption.min_duration ≈ 0.5s`、`resume_false_interruption`（假打断后恢复播放）、`min_words` 过滤"嗯/对"、adaptive 模式用模型区分 backchannel。[Turns](https://docs.livekit.io/agents/logic/turns/) / [参数表](https://docs.livekit.io/reference/agents/turn-handling-options/)
- **Vivoka**（商用 Android SDK）：`VOICE_COMMUNICATION` + 自家 AEC，用 10ms 粒度 speech/silence 事件，**连续语音 >200ms 且 TTS 正在合成**才判打断。[文档](https://doc.vivoka.com/vdk/latest/how-to-setup-a-barge-in)
- 三家共同点：**判定层与执行层分离**；执行层永远"立即丢弃未播音频"。

## 4. 坑清单

1. `requestFocus()` 用 `focusRequest != null` 早退：一次 LOSS 后再不重申请，之后全程无焦点。
2. 多处 request/abandon 共用 clientId，一处 abandon 全体失焦 → FocusOwner 单点。
3. `LOSS_TRANSIENT` 里调 `stop()`：无法续播；且没 flush，下次 `begin()` 先放上一句尾巴。
4. 没处理 `LOSS_TRANSIENT_CAN_DUCK`：别人要压你，你直接停 → 用户听感"随机断句"。
5. `USAGE_MEDIA` 让系统按音乐处理（通知/导航会 duck 你）；语音用 `USAGE_ASSISTANT`（部分 ROM 路由/音量策略有差异，`待确认`）。
6. 桌宠音效别用 `USAGE_ASSISTANCE_SONIFICATION`：走 STREAM_SYSTEM，媒体音量键控不到、静音模式直接没声。
7. `getMinBufferSize * 2` 的 buffer 拉长 abort 尾巴；abort 顺序 `setVolume(0f)` → `pause()` → `flush()` 再省 10–30ms。
8. `playEncodedBlocking` 用 MediaPlayer + `sleep(40)` 轮询，最长阻塞 30s 且**外部打不断** → 删掉，统一走 PCM track。
9. `write()` 与 `stop()` 并发会短写（官方原文），写循环不校验返回值 → 打断后串音/异常。
10. 系统 `TextToSpeech.stop()` 是跨进程异步，**无法保证 <100ms 静音**；读屏通道只能"播前抑制"。
11. 打断后只把"已播出"的文本写回 history（Pipecat 做法），否则模型以为自己说完了，下一轮语义错位。
12. AEC 只在外放时有意义；耳机/蓝牙没有回声问题，但"用户在说话"的检测仍要自己做。
13. 打断判定必须防抖：单帧能量突增（关门/敲桌）会误触发 → 200–300ms 持续 + 最小字数 + 仅 TTS 播放中生效。
14. "混音"与"互斥"是两件事：短音效应允许叠在语音上（duck 到 0.3），别塞进独占租约，否则桌宠说话时会变哑。
15. 采样率不统一的坑已在 `prepareRate()` 处理，但**重建 track 会丢掉 focus 之外的播放头基准**；跨采样率切句要在句子边界做，别在词中间。

## 5. 结论：SpeechGate 照哪个方案做

**照 Pipecat 的四段式，只做其中两段**：`判定（要不要打断） → 执行（多快静音） → 上下文（说到哪） → 恢复（能不能续播）`。`SpeechGate` 只负责**执行 + 优先级**，判定和 context 修补留在调用方（`VoiceLoop`）。

保留 ✅

- `Lease` / 优先级抢占 / 90s 泄漏回收 / `onEvicted` 同步静音——设计是对的，无需改。
- `abort()` 顺序固化为 `setVolume(0f)` → `pause()` → `flush()` → `onEvicted()`，全程 <1 帧。
- 新增 `softAbort()`：停止投喂（cancel SSE/LLM）+ 不 cancel 写协程 + 等 drain（`playbackHeadPosition` 到点）+ 3s 超时兜底 → 释放租约。这就是"说完最后一句"。
- AudioFocus 收进 `SpeechGate`：按当前 owner 的 `Priority` 在 `TRANSIENT` / `MAY_DUCK` 两档里选。
- 音效走 `SoundPool`（独立流、可与语音混音），不参与 gate。

砍掉 ❌

- 真 barge-in / AEC 常开麦克风——本期不做，留产品开关与后续灰度。
- `SpeechRecognizer` 常驻监听——收益低、延迟高、机型差异大。
- 每个 `SpeechPlayer` 各自 request/abandon AudioFocus。
- `setPlaybackParams` 变速（会变调，除非改用 Media3/ExoPlayer 的 time-stretch）。
- `playEncodedBlocking` 这条 MediaPlayer 降级通路。

若以后要做 C 路：只在外放（`AudioDeviceInfo.TYPE_BUILTIN_SPEAKER`）启用；阈值 300ms / 2 字；误触发率 <5% 再放量；失败自动回落点击打断。
