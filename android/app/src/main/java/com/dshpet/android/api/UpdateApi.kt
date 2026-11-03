package com.dshpet.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * GitHub Releases 查询（更新检查）。
 *
 * 从 `pet/Updater.kt` 里搬出来的：那边原来是"业务判断 + HTTP 请求"混在一起，
 * 而且自己 new 了一个 OkHttpClient。现在网络部分归这里，[com.dshpet.android.pet.Updater]
 * 只保留"版本号比较"这类纯逻辑。
 */
object UpdateApi {

    private const val REPO = "OTFiles/dsh-pet-inAndroid"
    const val RELEASES_URL = "https://github.com/$REPO/releases"

    data class Release(val tag: String, val url: String)

    /** 挂起版（新代码用这个） */
    suspend fun latest(timeoutSec: Long = 10): Result<Release> = withContext(Dispatchers.IO) {
        latestBlocking(timeoutSec)
    }

    /**
     * 阻塞版：给已有的同步调用点（MainActivity / PetOverlayService 的线程池任务）用。
     * 内部走 [ApiClient.read]，连接池与全 App 共享。
     */
    fun latestBlocking(timeoutSec: Long = 10): Result<Release> = try {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        ApiClient.read(timeoutSec).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Result.failure(IllegalStateException("无法连接更新服务（HTTP ${resp.code}）"))
            } else {
                val json = JSONObject(resp.body?.string().orEmpty())
                val tag = json.optString("tag_name", "")
                if (tag.isBlank()) {
                    Result.failure(IllegalStateException("无法连接更新服务，请稍后重试"))
                } else {
                    Result.success(Release(tag, json.optString("html_url", RELEASES_URL)))
                }
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
