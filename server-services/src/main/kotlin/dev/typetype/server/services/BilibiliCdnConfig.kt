package dev.typetype.server.services

object BilibiliCdnConfig {
    const val CDN_REGION_ENV = "BILIBILI_CDN_REGION"
    const val SERVER_REGION_ENV = "BILIBILI_SERVER_REGION"

    fun defaultRegion(getEnv: (String) -> String? = { System.getenv(it) ?: System.getProperty(it) }): String {
        val region = getEnv(CDN_REGION_ENV) ?: getEnv(SERVER_REGION_ENV)
        return when (region?.trim()?.lowercase()) {
            "overseas", "oversea", "ov", "foreign" -> "overseas"
            "domestic", "cn", "china", "mainland" -> "domestic"
            else -> "auto"
        }
    }

    fun resolveRegion(clientRegion: String?, getEnv: (String) -> String? = { System.getenv(it) ?: System.getProperty(it) }): String {
        return when (clientRegion?.trim()?.lowercase()) {
            "overseas", "oversea", "ov" -> "overseas"
            "domestic", "cn", "mainland" -> "domestic"
            "auto" -> "auto"
            else -> defaultRegion(getEnv)
        }
    }
}
