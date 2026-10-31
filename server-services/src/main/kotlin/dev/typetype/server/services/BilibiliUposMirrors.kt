package dev.typetype.server.services

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val UPOS_MIRROR_HOST = Regex("""upos-[a-z]+-mirror[a-z0-9]+\.(bilivideo\.com|akamaized\.net)""")

val ALL_UPOS_MIRROR_HOSTS = listOf(
    "upos-sz-mirroraliov.bilivideo.com",
    "upos-sz-mirrorcosov.bilivideo.com",
    "upos-sz-mirrorali.bilivideo.com",
    "upos-sz-mirrorhw.bilivideo.com",
    "upos-sz-mirrorcos.bilivideo.com",
    "upos-hz-mirrorakam.akamaized.net",
)

fun bilibiliUposMirrorAlternates(
    url: String,
    isAkamaiBlacklisted: Boolean = BilibiliAkamaiConfig.isAkamaiBlacklisted(),
): List<String> {
    val parsed = url.toHttpUrlOrNull() ?: return emptyList()
    if (!UPOS_MIRROR_HOST.matches(parsed.host)) return emptyList()
    val availableHosts = if (isAkamaiBlacklisted) {
        ALL_UPOS_MIRROR_HOSTS.filter { !BilibiliAkamaiConfig.isAkamaiHost(it) }
    } else {
        ALL_UPOS_MIRROR_HOSTS
    }
    return availableHosts.filter { it != parsed.host }.map { parsed.newBuilder().host(it).build().toString() }
}

fun prioritizeUposMirrors(urls: List<String>): List<String> {
    return urls.sortedBy { url ->
        val host = url.toHttpUrlOrNull()?.host
        val index = if (host != null) ALL_UPOS_MIRROR_HOSTS.indexOf(host) else -1
        if (index >= 0) index else Int.MAX_VALUE
    }
}
