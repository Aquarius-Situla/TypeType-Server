package dev.typetype.server.services

object YoutubeSessionCookieJar {
    fun merge(existingHeader: String, setCookieHeaders: List<String>): String {
        val values = linkedMapOf<String, String>()
        for (part in existingHeader.split(';')) {
            val index = part.indexOf('=')
            if (index <= 0) continue
            val name = part.take(index).trim()
            val value = part.drop(index + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) values[name] = value
        }
        for (header in setCookieHeaders) {
            val pair = header.substringBefore(';')
            val index = pair.indexOf('=')
            if (index <= 0) continue
            val name = pair.take(index).trim()
            val value = pair.drop(index + 1).trim()
            if (!YoutubeSessionCookieNormalizer.isAllowedName(name)) continue
            if (value.isEmpty()) values.remove(name) else values[name] = value
        }
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
