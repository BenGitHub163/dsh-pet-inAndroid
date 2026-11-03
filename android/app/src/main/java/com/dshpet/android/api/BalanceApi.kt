package com.dshpet.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * **余额接口**：`GET {chatBaseUrl}/user/balance`。
 *
 * 重构点：`pet/Balance.kt` 原本同时承担「HTTP 请求 + JSON 解析」和「余额时段/档位文案」，
 * 两件事变化原因完全不同。现在：
 *  - 本类只管请求与响应解析（域名、鉴权、字段都在这）；
 *  - `pet/Balance.kt` 只保留纯逻辑（档位、时段文案、动画索引），不再碰网络。
 */
object BalanceApi {

    /** 查询余额，返回可直接展示的中文文本（与旧实现保持一致的措辞） */
    suspend fun fetch(
        baseUrl: String,
        apiKey: String,
        verifySsl: Boolean = true,
        timeoutSec: Int = 15,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("未配置 API Key"))
        try {
            val url = baseUrl.trim().trimEnd('/') + "/user/balance"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            ApiClient.config(
                connectSec = timeoutSec.toLong(),
                readSec = timeoutSec.toLong(),
                writeSec = timeoutSec.toLong(),
                verifySsl = verifySsl,
            ).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${resp.code}（该端点可能不支持余额查询）"),
                    )
                }
                val body = resp.body?.string().orEmpty()
                val info = JSONObject(body).optJSONArray("balance_infos")?.optJSONObject(0)
                    ?: return@withContext Result.failure(IllegalStateException("响应中没有余额信息"))
                val total = info.optString("total_balance", "")
                if (total.isBlank()) return@withContext Result.success("余额信息为空")
                val granted = info.optString("granted_balance", "")
                val topped = info.optString("topped_up_balance", "")
                Result.success("$total 元（赠送 $granted / 充值 $topped）")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}