package com.dshpet.android.voice.context

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings
import android.util.Log
import com.dshpet.android.voice.notify.PetNotificationListener

/**
 * 硬件与媒体感知（**Provider**）。
 *
 * 能拿到什么：
 *  - 音频通路：手机扬声器 / 有线耳机 / 蓝牙耳机 / 蓝牙音箱 / USB 耳机 / BLE 助听设备
 *  - 正在播放的媒体：标题 + 艺人 + 来源应用（通过 MediaSessionManager）
 *
 * 为什么不做"读通知正文"：
 *  MediaSessionManager 只给我们媒体会话的元数据（歌名/视频标题），
 */
class MediaDevicesProvider(private val ctx: Context) {

    data class MediaNow(val title: String?, val artist: String?, val app: String?)

    /** 有没有通知使用权（MediaSessionManager.getActiveSessions 需要它） */
    fun hasNotificationAccess(): Boolean = try {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        flat?.contains(ctx.packageName) == true
    } catch (e: Throwable) {
        false
    }

    fun openNotificationAccess() {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 当前音频输出通路。桌宠能说出"你现在戴耳机"这种话，比"我知道你在听歌"更自然。
     */
    fun audioRoute(): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return "未知"
            val types = devices.map { it.type }.toSet()

            when {
                AudioDeviceInfo.TYPE_BLE_HEADSET in types || AudioDeviceInfo.TYPE_BLE_SPEAKER in types ->
                    "蓝牙耳机（低功耗）"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types || AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types ->
                    "蓝牙耳机/音箱"
                AudioDeviceInfo.TYPE_USB_HEADSET in types || AudioDeviceInfo.TYPE_USB_DEVICE in types ->
                    "USB 耳机"
                AudioDeviceInfo.TYPE_WIRED_HEADSET in types || AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types ->
                    "有线耳机"
                AudioDeviceInfo.TYPE_HEARING_AID in types -> "助听设备"
                else -> "手机扬声器"
            }
        } catch (e: Throwable) {
            Log.w(TAG, "audioRoute failed: ${e.message}")
            "未知"
        }
    }

    /** 正在播放的媒体（拿不到就 null，绝不猜） */
    fun mediaNow(): MediaNow? {
        if (!hasNotificationAccess()) return null
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
            val cn = ComponentName(ctx, PetNotificationListener::class.java)
            val sessions: List<MediaController> = msm.getActiveSessions(cn)
            val playing = sessions.lastOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
                ?: sessions.lastOrNull()
            val md = playing?.metadata ?: return null
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            val pkg = playing.packageName
            val app = UsageStatsAppSource.KNOWN[pkg] ?: pkg.substringAfterLast('.')
            if (title.isNullOrBlank() && artist.isNullOrBlank()) null else MediaNow(title, artist, app)
        } catch (e: SecurityException) {
            Log.w(TAG, "media session needs notification access")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "mediaNow failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DshVoiceMedia"
    }
}
