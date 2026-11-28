package dev.typetype.server.ai

import dev.typetype.server.services.SecretConfigReader

data class GeminiConfig(
    val apiKey: String?,
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val thinkingLevel: String? = null,
    val fallbackModels: List<String> = emptyList(),
) {
    val enabled: Boolean
        get() = !apiKey.isNullOrBlank()

    val models: List<String> = parseModels(model, fallbackModels)

    val primaryModel: String
        get() = models.first()

    fun thinkingLevelFor(targetModel: String): String? {
        val level = thinkingLevel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (LEGACY_NO_THINKING_PREFIXES.any { targetModel.startsWith(it, ignoreCase = true) }) {
            return null
        }
        return level
    }

    companion object {
        const val DEFAULT_MODEL: String = "gemini-3.8-flash"
        const val DEFAULT_THINKING_LEVEL: String = "medium"
        const val DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com"
        private val DEFAULT_THINKING_PREFIXES = listOf("gemini-3.", "gemini-4")
        private val LEGACY_NO_THINKING_PREFIXES = listOf("gemini-1.", "gemini-2.")
        private val ALLOWED_THINKING_LEVELS = setOf("minimal", "low", "medium", "high")

        fun fromEnvironment(
            env: (String) -> String? = System::getenv,
            secret: (String) -> String? = SecretConfigReader::read,
        ): GeminiConfig {
            val apiKey = secret("AI_GEMINI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
            val parsedModels = parseModelList(env("AI_GEMINI_MODEL"))
            val thinkingLevel = parseThinkingLevel(env("AI_GEMINI_THINKING_LEVEL"), parsedModels)
            val baseUrl = env("AI_GEMINI_BASE_URL")
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_BASE_URL
            return GeminiConfig(
                apiKey = apiKey,
                model = parsedModels.first(),
                baseUrl = baseUrl,
                thinkingLevel = thinkingLevel,
                fallbackModels = parsedModels.drop(1),
            )
        }

        fun parseModelList(raw: String?): List<String> {
            val parsed = raw
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()
            return parsed.ifEmpty { listOf(DEFAULT_MODEL) }
        }

        private fun parseModels(primary: String, fallbacks: List<String>): List<String> {
            val combined = (listOf(primary) + fallbacks)
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            return combined.ifEmpty { listOf(DEFAULT_MODEL) }
        }

        private fun parseThinkingLevel(raw: String?, models: List<String>): String? {
            val defaultLevel = DEFAULT_THINKING_LEVEL.takeIf { models.any(::supportsDefaultThinking) }
            val normalized = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return defaultLevel
            if (normalized == "off" || normalized == "none") return null
            return normalized.takeIf { it in ALLOWED_THINKING_LEVELS } ?: defaultLevel
        }

        private fun supportsDefaultThinking(targetModel: String): Boolean =
            DEFAULT_THINKING_PREFIXES.any { targetModel.startsWith(it, ignoreCase = true) }
    }
}
