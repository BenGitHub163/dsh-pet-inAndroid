package com.dshpet.android.util

/**
 * 方法级"自动日志"标记（**AOP 切入点**）。
 *
 * 加了这个注解的方法，编译期会被 `LogAopPlugin`（buildSrc 里的 ASM 插桩）改写成：
 *
 * ```kotlin
 * fun speak(text: String) {            // 原方法
 *   AppTrace.enter("VoiceLoop", "speak", "text=${text.take(40)}")
 *   try { … 原逻辑 … } finally { AppTrace.exit("VoiceLoop", "speak", 12) }
 * }
 * ```
 *
 * 也就是说**不用在业务代码里写一行 Log**，方法进出、耗时、线程、关键参数都会自动进日志。
 *
 * 用法约定：
 *  - 只标**模块边界**（Service 生命周期、语音状态流转、API 调用、工具执行），
 *    别标 getter / 循环体，否则日志会被刷爆；
 *  - 参数默认只记**长度/摘要**，不记原文（隐私：对话内容、key 都不能进日志）；
 *  - 日志等级见 [AppTrace.Level]。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class TraceLog(
    /** 模块名，比如 "Voice" / "Pet" / "API"；留空则取类名 */
    val module: String = "",
    /** 日志等级：V/D/I/W */
    val level: String = "D",
    /** 是否记录耗时（默认记） */
    val elapsed: Boolean = true,
)
