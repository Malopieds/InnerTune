package com.malopieds.innertune.utils

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.json.JSONObject
import org.json.JSONArray

object Updater {
    private val client = HttpClient()
    var lastCheckTime = -1L
        private set

    suspend fun getLatestVersionName(checkForPrereleases: Boolean): Result<String> =
        runCatching {
            val latestResponse = client.get("https://api.github.com/repos/Malopieds/InnerTune/releases/latest").bodyAsText()
            val latestJson = JSONObject(latestResponse)

            if (checkForPrereleases) {
                val latestPublishedTime = latestJson.getString("published_at")

                val releasesResponse = client.get("https://api.github.com/repos/Malopieds/InnerTune/releases").bodyAsText()
                val releasesJson = JSONArray(releasesResponse)

                repeat(releasesJson.length()) { index ->
                    val currentReleaseJson = releasesJson.getJSONObject(index)
                    val publishedTime = currentReleaseJson.getString("published_at")
                    if (publishedTime.compareTo(latestPublishedTime) > 0) {
                        val isPrerelease = currentReleaseJson.getBoolean("prerelease")
                        if (isPrerelease) {
                            val versionName = currentReleaseJson.getString("name")
                            lastCheckTime = System.currentTimeMillis()
                            return Result.success(versionName)
                        }
                    } else {
                        return@repeat
                    }
                }
            }

            val versionName = latestJson.getString("name")

            lastCheckTime = System.currentTimeMillis()
            versionName
        }
}
