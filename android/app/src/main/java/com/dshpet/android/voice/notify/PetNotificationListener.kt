package com.dshpet.android.voice.notify

import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 一个**刻意什么都不做**的通知监听服务。
 *
 * 它存在的唯一目的：拿到 MediaSessionManager 的授权
 * （AudioManager/MediaSession 的"正在播放"信息需要通知使用权）。
 *
 * 我们**不读取、不存储、不上传任何通知内容**——onNotificationPosted 直接丢弃。
 * 这样既能说出"你在听什么歌"，又不会碰到别人的私聊。
 */
class PetNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        // 明确不做任何事：不解析、不缓存、不上报
    }

    override fun onListenerConnected() {
        Log.i(TAG, "notification access granted (media session only)")
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "notification access revoked")
    }

    private companion object {
        const val TAG = "DshVoiceNotify"
    }
}
