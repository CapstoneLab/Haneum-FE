package com.han2.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String
)

object AppUpdateManager {
    private val executor = Executors.newSingleThreadExecutor()

    fun check(onResult: (Result<AppUpdateInfo?>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL.trim()
                if (manifestUrl.isEmpty()) return@runCatching null
                requireHttps(manifestUrl)
                val connection = URI(manifestUrl).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                try {
                    if (connection.responseCode !in 200..299) error("업데이트 서버 응답 오류")
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val info = AppUpdateInfo(
                        versionCode = json.getInt("versionCode"),
                        versionName = json.getString("versionName"),
                        apkUrl = json.getString("apkUrl"),
                        releaseNotes = json.optString("releaseNotes", "")
                    )
                    requireHttps(info.apkUrl)
                    if (info.versionCode > BuildConfig.VERSION_CODE) info else null
                } finally {
                    connection.disconnect()
                }
            }
            onResult(result)
        }
    }

    private fun requireHttps(url: String) {
        require(URI(url).scheme.equals("https", ignoreCase = true)) { "HTTPS 주소만 사용할 수 있습니다." }
    }
}
