package com.dshpet.android.pet

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 桌宠视频渲染层：**一个容器 + 两个 SurfaceView 图层**（alpha 叠加方案）。
 *
 * 背景（Android 12+ `FLAG_NOT_TOUCHABLE` 的规则）：可点击穿透的悬浮窗会被系统压到
 * `MAXIMUM_OBSCURING_OPACITY = 0.8`（实测 SurfaceFlinger `alpha=0.799805`）。
 * 这个限制是**按窗口图层**算的，而 SurfaceView 的 surface 是**独立子图层** —— 两个子图层
 * 各自被压 0.8，叠在一起按 alpha 合成：1-(1-0.8)² = **0.96**，视觉上几乎等于不透明。
 *
 * 结构：
 *  - 一个 ExoPlayer（唯一解码器）→ [videoSt]（外部纹理采样源）；
 *  - 一个 EGL context，渲染到 [outA]/[outB] 两个 SurfaceView 各自的 EGLSurface；
 *    每帧 `updateTexImage()` **只调一次**，然后把同一个四边形画到两个目标上（不会两路解码器跑偏）。
 *
 * 对外 API 与之前完全一致（play/setMirror/setContentAlpha/setPlaybackSpeed/pause/resume/…）。
 */
class PetVideoView(context: Context) : FrameLayout(context) {

    /** XML 布局 inflate 需要 (Context, AttributeSet) 构造函数 */
    constructor(context: Context, attrs: AttributeSet?) : this(context)

    interface Listener {
        fun onVideoEnded(name: String)
        fun onVideoError(name: String, msg: String)

        /** 渲染初始化彻底失败（黑屏 / 空白桌宠）。stage/reason 是固定值，便于 grep 定责 */
        fun onRenderBroken(stage: String, reason: String) {}
    }

    private object RenderStage {
        const val SHADER = "SHADER"
        const val EGL = "EGL"
        const val PLAYER = "PLAYER"
        const val FIRST_FRAME = "FIRST_FRAME"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val glThread = HandlerThread("pet-glstack").apply { start() }
    private val glHandler = Handler(glThread.looper)

    // ---- 两个输出图层（各自是独立合成图层，alpha 会各自被压 → 叠加后 ~0.96）----
    private val outA = SurfaceView(context).apply { holder.setFormat(PixelFormat.TRANSLUCENT) }
    private val outB = SurfaceView(context).apply { holder.setFormat(PixelFormat.TRANSLUCENT) }

    // ---- 播放状态 ----
    private var listener: Listener? = null
    private var ready = false
    private var currentName: String? = null
    private var firstFrameRendered = false
    private var framesDrawn = 0
    private var pendingPlay: Triple<String, String, Float>? = null
    private var lastPlayed: Triple<String, String, Float>? = null
    private val fileFallbackTried = HashSet<String>()

    private var gpuInfo = "unknown"
    private var brokenReported = false

    // ---- GL / EGL ----
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    /** 每个输出 surface 一个 EGLSurface（渲染目标） */
    private val targets = LinkedHashMap<Surface, EGLSurface>()

    /** 采样源：ExoPlayer 解码输出 */
    private var videoSt: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var textureId = 0
    private var program = 0
    private var uTexLoc = 0
    private var uMirrorLoc = 0
    private var uAlphaLoc = 0
    private var uMLoc = 0
    private var mirror = false
    private val stMatrix = FloatArray(16)

    @Volatile
    private var contentAlpha = 1f

    @Volatile
    private var dirty = false
    private val drawRunnable = Runnable { drawOnce() }

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        val n = currentName
                        if (n != null) mainHandler.post { listener?.onVideoEnded(n) }
                    } else if (state == Player.STATE_READY) {
                        firstFrameWatchdog()
                    }
                }

                override fun onRenderedFirstFrame() {
                    firstFrameRendered = true
                    com.dshpet.android.util.AppLog.log("VIDEO", "首帧已渲染: $currentName")
                    val rt = Runtime.getRuntime()
                    com.dshpet.android.util.AppLog.log(
                        "MEM", "首帧时: 堆已用${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB"
                    )
                }

                override fun onPlayerError(error: PlaybackException) {
                    val n = currentName
                    if (tryFileFallback()) return
                    if (!firstFrameRendered) {
                        reportBroken(RenderStage.PLAYER, "player-error-before-first-frame", error.message ?: "")
                    }
                    if (n != null) mainHandler.post { listener?.onVideoError(n, error.message ?: "播放失败") }
                }
            })
        }
    }

    init {
        addView(outA, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(outB, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        outA.holder.addCallback(SurfaceCb())
        outB.holder.addCallback(SurfaceCb())
    }

    fun setListener(l: Listener) {
        listener = l
    }

    // ================================================================ 输出 surface 生命周期

    private inner class SurfaceCb : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val s = holder.surface
            glHandler.post { addTarget(s) }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            glHandler.post { addTarget(holder.surface) }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            val s = holder.surface
            glHandler.post { removeTarget(s) }
        }
    }

    /** 给某个输出 surface 建 EGLSurface（必要时先建 context），并加入渲染目标 */
    private fun addTarget(surface: Surface) {
        if (!surface.isValid) return
        try {
            ensureContext(surface)
            if (targets.containsKey(surface)) return
            val es = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (es == null || es == EGL14.EGL_NO_SURFACE) {
                com.dshpet.android.util.AppLog.log("GL", "eglCreateWindowSurface 失败（目标数=${targets.size}）")
                return
            }
            targets[surface] = es
            com.dshpet.android.util.AppLog.log("GL", "渲染目标 +1 → 共 ${targets.size} 个")
            requestDraw()
        } catch (e: Throwable) {
            com.dshpet.android.util.AppLog.log("GL", "addTarget 失败: ${e.javaClass.simpleName} ${e.message}")
            reportBroken(RenderStage.EGL, "add-target-failed", e.message ?: "")
        }
    }

    private fun removeTarget(surface: Surface) {
        val es = targets.remove(surface) ?: return
        runCatching { EGL14.eglDestroySurface(eglDisplay, es) }
        com.dshpet.android.util.AppLog.log("GL", "渲染目标 -1 → 剩 ${targets.size} 个")
    }

    private fun ensureContext(firstSurface: Surface) {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("eglGetDisplay 失败")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) throw IllegalStateException("eglInitialize 失败")
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, configs, 0, 1, num, 0) || num[0] <= 0) {
            throw IllegalStateException("eglChooseConfig 失败")
        }
        eglConfig = configs[0] ?: throw IllegalStateException("config=null")
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext 失败")
        // 先用第一个 surface 把 context current 起来，才能编译着色器/建纹理
        val tmp = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, firstSurface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        if (tmp == EGL14.EGL_NO_SURFACE) throw IllegalStateException("首个 eglCreateWindowSurface 失败")
        if (!EGL14.eglMakeCurrent(eglDisplay, tmp, tmp, eglContext)) throw IllegalStateException("eglMakeCurrent 失败")
        targets[firstSurface] = tmp

        gpuInfo = runCatching {
            val r = GLES20.glGetString(GLES20.GL_RENDERER) ?: "?"
            val v = GLES20.glGetString(GLES20.GL_VENDOR) ?: "?"
            val ver2 = GLES20.glGetString(GLES20.GL_VERSION) ?: "?"
            "$r|$v|$ver2"
        }.getOrDefault("unknown")

        GLES20.glClearColor(0f, 0f, 0f, 0f)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER, "normal")
        if (program == 0) {
            runCatching { GLES20.glReleaseShaderCompiler() }
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_SIMPLE, "simple")
        }
        if (program == 0) {
            reportBroken(RenderStage.SHADER, "compile-or-link-failed", "stack")
            return
        }
        uTexLoc = GLES20.glGetUniformLocation(program, "uTex")
        uMirrorLoc = GLES20.glGetUniformLocation(program, "uMirror")
        uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")
        uMLoc = GLES20.glGetUniformLocation(program, "uM")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val vst = SurfaceTexture(textureId)
        vst.setOnFrameAvailableListener { requestDraw() }
        videoSt = vst
        videoSurface = Surface(vst)

        ready = true
        com.dshpet.android.util.AppLog.log(
            "GL", "stack ready: gpu=$gpuInfo program=$program targets=${targets.size}"
        )
        mainHandler.post {
            player.setVideoSurface(videoSurface)
            pendingPlay?.let { doPlay(it.first, it.second, it.third); pendingPlay = null }
        }
    }

    // ================================================================ 播放控制

    fun play(name: String, assetPath: String, speed: Float) {
        if (!ready) {
            pendingPlay = Triple(name, assetPath, speed)
            return
        }
        doPlay(name, assetPath, speed)
    }

    private fun doPlay(name: String, assetPath: String, speed: Float) {
        currentName = name
        lastPlayed = Triple(name, assetPath, speed)
        com.dshpet.android.util.AppLog.log(
            "VIDEO", "play asset=pet/videos/$assetPath speed=$speed ready=$ready broken=$brokenReported"
        )
        val uri = android.net.Uri.parse("asset:///pet/videos/$assetPath")
        runCatching {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.playbackParameters = PlaybackParameters(speed)
            player.prepare()
            player.playWhenReady = true
        }.onFailure { e ->
            com.dshpet.android.util.AppLog.log("VIDEO", "play 抛异常 asset=$assetPath ${e.javaClass.simpleName}: ${e.message}")
            if (!tryFileFallback()) {
                mainHandler.post { listener?.onVideoError(name, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun tryFileFallback(): Boolean {
        val (_, path, speed) = lastPlayed ?: return false
        if (!fileFallbackTried.add(path)) return false
        return runCatching {
            val dir = java.io.File(context.filesDir, "pet_videos").apply { mkdirs() }
            val f = java.io.File(dir, path.replace('/', '_'))
            if (!f.exists() || f.length() == 0L) {
                context.assets.open("pet/videos/$path").use { input ->
                    f.outputStream().use { out -> input.copyTo(out) }
                }
            }
            com.dshpet.android.util.AppLog.log("VIDEO", "asset 失败 → 本地副本 ${f.length()}B path=$path")
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f)))
            player.playbackParameters = PlaybackParameters(speed)
            player.prepare()
            player.playWhenReady = true
            true
        }.getOrElse { e ->
            com.dshpet.android.util.AppLog.log("VIDEO", "本地副本也失败 path=$path ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun currentPositionMs(): Long =
        if (player.duration > 0 && player.duration != androidx.media3.common.C.TIME_UNSET) player.currentPosition else 0L

    fun setMirror(m: Boolean) {
        if (mirror == m) return
        mirror = m
        requestDraw()
    }

    fun setContentAlpha(a: Float) {
        val v = a.coerceIn(0f, 1f)
        if (v == contentAlpha) return
        contentAlpha = v
        requestDraw()
    }

    fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    fun pausePlay() {
        player.playWhenReady = false
    }

    fun resumePlay() {
        if (player.mediaItemCount > 0) {
            player.playWhenReady = true
        } else {
            lastPlayed?.let { (n, p, s) -> play(n, p, s) }
        }
    }

    fun release() {
        runCatching { player.release() }
        glHandler.post { releaseGl() }
        mainHandler.postDelayed({ runCatching { glThread.quitSafely() } }, 300)
    }

    private fun releaseGl() {
        runCatching {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                targets.values.forEach { runCatching { EGL14.eglDestroySurface(eglDisplay, it) } }
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        }
        targets.clear()
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        ready = false
    }

    // ================================================================ 绘制

    private fun requestDraw() {
        if (dirty) return
        dirty = true
        glHandler.post(drawRunnable)
    }

    private fun drawOnce() {
        dirty = false
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) return
        val vst = videoSt ?: return
        if (targets.isEmpty()) return
        try {
            // 找一个可用的目标来 current，才能 updateTexImage
            val firstTarget = targets.values.firstOrNull() ?: return
            if (!EGL14.eglMakeCurrent(eglDisplay, firstTarget, firstTarget, eglContext)) return
            try {
                vst.updateTexImage()
            } catch (e: Throwable) {
                targets.values.forEach { es ->
                    runCatching {
                        EGL14.eglMakeCurrent(eglDisplay, es, es, eglContext)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        EGL14.eglSwapBuffers(eglDisplay, es)
                    }
                }
                return
            }
            vst.getTransformMatrix(stMatrix)
            framesDrawn++
            // 同一帧画到每个图层（updateTexImage 只调一次，两个图层内容完全同步）
            var drawn = 0
            targets.values.forEach { es ->
                if (EGL14.eglMakeCurrent(eglDisplay, es, es, eglContext)) {
                    drawQuad()
                    if (EGL14.eglSwapBuffers(eglDisplay, es)) drawn++
                }
            }
            if (framesDrawn == 90) {
                runCatching {
                    val bb = ByteBuffer.allocateDirect(4)
                    val px = (width * 0.5f).toInt().coerceIn(0, (width - 1).coerceAtLeast(0))
                    val py = (height * 0.3f).toInt().coerceIn(0, (height - 1).coerceAtLeast(0))
                    GLES20.glReadPixels(px, py, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
                    com.dshpet.android.util.AppLog.log(
                        "GL", "bodyAlpha a=${bb.get(3).toInt() and 0xFF} uAlpha=$contentAlpha targets=${targets.size}"
                    )
                }
            }
            if (framesDrawn % 150 == 0) {
                com.dshpet.android.util.AppLog.log(
                    "GL", "已渲染 $framesDrawn 帧（目标 $drawn 个），firstFrame=$firstFrameRendered name=$currentName"
                )
            }
        } catch (e: Throwable) {
            com.dshpet.android.util.AppLog.log("GL", "draw 失败: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun drawQuad() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexLoc, 0)
        GLES20.glUniform1f(uMirrorLoc, if (mirror) 1f else 0f)
        GLES20.glUniform1f(uAlphaLoc, contentAlpha)
        GLES20.glUniformMatrix4fv(uMLoc, 1, false, stMatrix, 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
    }

    private fun firstFrameWatchdog(retry: Int = 0) {
        if (firstFrameRendered) return
        mainHandler.postDelayed({
            if (!firstFrameRendered) {
                com.dshpet.android.util.AppLog.log(
                    "VIDEO", "就绪后 5s 未渲染首帧！ready=$ready name=$currentName retry=$retry"
                )
                if (retry == 0) {
                    runCatching {
                        player.setVideoSurface(null)
                        player.setVideoSurface(videoSurface)
                        player.pause()
                        player.seekTo(0)
                        player.playWhenReady = true
                        requestDraw()
                        firstFrameWatchdog(1)
                    }.onFailure { firstFrameWatchdog(1) }
                } else {
                    reportBroken(RenderStage.FIRST_FRAME, "no-frame-after-retry", "ready=$ready")
                }
            }
        }, 5000)
    }

    private fun reportBroken(stage: String, reason: String, detail: String = "") {
        if (brokenReported) return
        brokenReported = true
        runCatching {
            com.dshpet.android.util.AppLog.log(
                "RENDER",
                "broken stage=$stage reason=$reason gpu=$gpuInfo model=${android.os.Build.MODEL} " +
                    "brand=${android.os.Build.BRAND} sdk=${android.os.Build.VERSION.SDK_INT} detail=${detail.take(160)}",
            )
            com.dshpet.android.util.AppLog.u("桌宠", "画面初始化失败：$stage/$reason（详情见「关于 → 查看日志」）")
        }
        mainHandler.post { listener?.onRenderBroken(stage, reason) }
    }

    companion object {

        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }

        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            uniform mat4 uM;
            varying vec2 vUV;
            void main() {
                vUV = (uM * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform float uMirror;
            uniform float uAlpha;
            varying vec2 vUV;
            void main() {
                float x = uMirror > 0.5 ? (1.0 - vUV.x) : vUV.x;
                vec4 rgb = texture2D(uTex, vec2(x * 0.5, vUV.y));
                float a = texture2D(uTex, vec2(0.5 + x * 0.5, vUV.y)).r;
                float av = a * uAlpha;
                gl_FragColor = vec4(rgb.rgb * av, av);
            }
        """

        private const val FRAGMENT_SHADER_SIMPLE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform float uMirror;
            uniform float uAlpha;
            varying vec2 vUV;
            void main() {
                float x = vUV.x;
                if (uMirror > 0.5) { x = 1.0 - x; }
                vec4 rgb = texture2D(uTex, vec2(x * 0.5, vUV.y));
                float aMask = texture2D(uTex, vec2(0.5 + x * 0.5, vUV.y)).r;
                float av = aMask * uAlpha;
                gl_FragColor = vec4(rgb.rgb * av, av);
            }
        """

        private fun createProgram(vertex: String, fragment: String, tag: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex, tag)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment, tag)
            if (vs == 0 || fs == 0) return 0
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glBindAttribLocation(p, 0, "aPos")
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = runCatching { GLES20.glGetProgramInfoLog(p) }.getOrDefault("")
                Log.e("PetVideoView", "[$tag] link error: $log")
                com.dshpet.android.util.AppLog.log("GL", "link error($tag): ${log.take(160)}")
                runCatching { GLES20.glDeleteProgram(p) }
                return 0
            }
            return p
        }

        private fun compile(type: Int, src: String, tag: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val status = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = runCatching { GLES20.glGetShaderInfoLog(sh) }.getOrDefault("")
                Log.e("PetVideoView", "[$tag] compile error: $log")
                com.dshpet.android.util.AppLog.log("GL", "compile error($tag): ${log.take(160)}")
                runCatching { GLES20.glDeleteShader(sh) }
                return 0
            }
            return sh
        }
    }
}
