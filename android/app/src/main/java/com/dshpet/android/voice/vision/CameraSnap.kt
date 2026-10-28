package com.dshpet.android.voice.vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 前摄抓一帧（**只在用户主动点"看一眼我"时调用**，不后台开机、不持续预览）。
 *
 * 隐私设计：
 *  - 不落盘：拿到 JPEG 后直接压缩进内存，用完即弃；
 *  - 不连拍：一次调用 = 一张图，拿不到就返回 null；
 *  - 不静默：只能由界面上的按钮触发（Activity 里显式调用）。
 */
class CameraSnap(private val ctx: Context) {

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** 返回压缩后的 JPEG（长边 ≤ 768），失败返回 null */
    suspend fun capture(maxWidth: Int = 768, timeoutMs: Long = 9000): ByteArray? {
        if (!hasPermission()) return null
        return withTimeoutOrNull(timeoutMs) { captureInternal(maxWidth) }
    }

    /**
     * 连拍：默认拍 [count] 张、间隔 [intervalMs]。
     *
     * 为什么不开一次相机会话连拍三帧：`ImageReader` 是 maxImages=1 的单帧管线，
     * 要在同一会话里连拍得改成一帧读完再 `capture()` 下一帧的状态机（还要处理帧间抖动）。
     * 这里选"每张各开一次相机"——慢一点（每张 0.3~0.6s），但代码简单、失败可降级
     * （中间某张失败就不收它），而"看一眼"本来就是低频动作。
     *
     * 返回值可能少于 [count]（拍到几张给几张），全失败返回空列表。
     */
    suspend fun captureBurst(
        count: Int = 3,
        intervalMs: Long = 200,
        maxWidth: Int = 768,
    ): List<ByteArray> {
        if (!hasPermission()) return emptyList()
        val out = ArrayList<ByteArray>(count)
        repeat(count) { i ->
            val b = capture(maxWidth, timeoutMs = 6000)
            if (b != null) out.add(b)
            if (i < count - 1) kotlinx.coroutines.delay(intervalMs)
        }
        return out
    }

    private suspend fun captureInternal(maxWidth: Int): ByteArray? = suspendCancellableCoroutine { cont ->
        val thread = HandlerThread("pet-cam").apply { start() }
        val handler = Handler(thread.looper)
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

        var device: CameraDevice? = null
        var reader: ImageReader? = null
        var done = false

        fun finish(result: ByteArray?) {
            if (done) return
            done = true
            runCatching { reader?.close() }
            runCatching { device?.close() }
            runCatching { thread.quitSafely() }
            if (cont.isActive) cont.resume(result)
        }

        try {
            val manager = cm ?: run { finish(null); return@suspendCancellableCoroutine }
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull() ?: run { finish(null); return@suspendCancellableCoroutine }

            reader = ImageReader.newInstance(maxWidth, maxWidth * 4 / 3, android.graphics.ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage()
                val bytes = try {
                    val buf = image.planes[0].buffer
                    val raw = ByteArray(buf.remaining()).also { buf.get(it) }
                    compress(raw, maxWidth)
                } catch (e: Throwable) {
                    Log.w(TAG, "read frame failed: ${e.message}")
                    null
                } finally {
                    runCatching { image.close() }
                }
                finish(bytes)
            }, handler)

            @Suppress("MissingPermission")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    runCatching {
                        val surface = reader!!.surface
                        val session = arrayListOf<android.hardware.camera2.params.OutputConfiguration>()
                        val builder = camera.createCaptureSessionByOutputConfigurations(
                            listOf(android.hardware.camera2.params.OutputConfiguration(surface)),
                            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                                    runCatching {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(surface)
                                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                        }
                                        s.capture(req.build(), null, handler)
                                    }.onFailure { finish(null) }
                                }

                                override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                                    finish(null)
                                }
                            },
                            handler
                        )
                        @Suppress("UNUSED_VARIABLE") val keep = session
                    }.onFailure {
                        Log.w(TAG, "session failed: ${it.message}")
                        finish(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    finish(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "camera error $error")
                    finish(null)
                }
            }, handler)
        } catch (e: Throwable) {
            Log.w(TAG, "capture failed: ${e.message}")
            finish(null)
        }
    }

    /** 缩放到长边 ≤ maxWidth 再压 JPEG，控制上传体积 */
    private fun compress(jpeg: ByteArray, maxWidth: Int): ByteArray? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val scale = maxWidth.toFloat() / maxOf(bmp.width, bmp.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            out.toByteArray()
        } catch (e: Throwable) {
            Log.w(TAG, "compress failed: ${e.message}")
            jpeg
        }
    }

    private companion object {
        const val TAG = "DshVoiceCam"
    }
}
