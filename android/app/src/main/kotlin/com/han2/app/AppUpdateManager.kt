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
                if (manifestUrl.isEmpty()) {
                    checkBackendAvailability()
                    return@runCatching null
                }
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

    private fun checkBackendAvailability() {
        check(BuildConfig.KEYBACK_BASE_URL.isNotBlank()) { "업데이트 확인 서버가 설정되지 않았습니다." }
        val healthUrl = BuildConfig.KEYBACK_BASE_URL.trimEnd('/') + "/health"
        val connection = URI(healthUrl).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        if (BuildConfig.KEYBACK_API_KEY.isNotBlank()) {
            connection.setRequestProperty("X-API-Key", BuildConfig.KEYBACK_API_KEY)
        }
        try {
            if (connection.responseCode !in 200..299) error("업데이트 확인 서버 응답 오류")
        } finally {
            connection.disconnect()
        }
    }
}
