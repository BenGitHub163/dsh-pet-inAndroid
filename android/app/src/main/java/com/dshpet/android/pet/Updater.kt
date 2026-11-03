package com.dshpet.android.pet

/**
 * GitHub Release 更新检查。
 *
 * 网络请求已经搬到 [com.dshpet.android.api.UpdateApi]（走全 App 共享的 ApiClient），
 * 这里只保留**纯逻辑**：版本号比较，以及对外保持原有调用形态，避免改动
 * MainActivity / PetOverlayService 里的调用点。
 */
object Updater {

    const val RELEASES_URL = com.dshpet.android.api.UpdateApi.RELEASES_URL

    /** 阻塞查询最新版本（调用方自己放到 IO 线程） */
    fun latestRelease(timeoutSec: Int = 10): Result<com.dshpet.android.api.UpdateApi.Release> =
        com.dshpet.android.api.UpdateApi.latestBlocking(timeoutSec.toLong())

    /** "v4.1.0" > "4.0.1" 这类 tag 比较 */
    fun isNewer(tag: String, current: String): Boolean {
        fun parse(v: String): List<Int> =
            v.trim().removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
        val a = parse(tag)
        val b = parse(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
