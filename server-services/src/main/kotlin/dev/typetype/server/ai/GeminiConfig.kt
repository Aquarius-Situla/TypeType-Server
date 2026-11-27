package dev.typetype.server.ai

import dev.typetype.server.services.SecretConfigReader

data class GeminiConfig(
    val apiKey: String?,
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val thinkingLevel: String? = null,
) {
    val enabled: Boolean
        get() = !apiKey.isNullOrBlank()

    companion object {
        const val DEFAULT_MODEL: String = "gemini-3.8-flash"
        const val DEFAULT_THINKING_LEVEL: String = "medium"
        const val DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com"
        private const val GEMINI_3_PREFIX = "gemini-3."
        private val ALLOWED_THINKING_LEVELS = setOf("minimal", "low", "medium", "high")

        fun fromEnvironment(
            env: (String) -> String? = System::getenv,
            secret: (String) -> String? = SecretConfigReader::read,
        ): GeminiConfig {
            val apiKey = secret("AI_GEMINI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
            val model = env("AI_GEMINI_MODEL")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL
            val thinkingLevel = parseThinkingLevel(env("AI_GEMINI_THINKING_LEVEL"), model)
            val baseUrl = env("AI_GEMINI_BASE_URL")
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_BASE_URL
            return GeminiConfig(
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl,
                thinkingLevel = thinkingLevel,
            )
        }

        private fun parseThinkingLevel(raw: String?, model: String): String? {
            val normalized = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: return DEFAULT_THINKING_LEVEL.takeIf { model.startsWith(GEMINI_3_PREFIX, ignoreCase = true) }
            if (normalized == "off" || normalized == "none") return null
            return normalized.takeIf { it in ALLOWED_THINKING_LEVELS }
                ?: DEFAULT_THINKING_LEVEL.takeIf { model.startsWith(GEMINI_3_PREFIX, ignoreCase = true) }
        }
    }
}
