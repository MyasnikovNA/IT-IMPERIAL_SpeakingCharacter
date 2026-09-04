/** Читает явную конфигурацию backend чата из переменных окружения. */
package speakingcharacter.config

/** Неизменяемая конфигурация, необходимая для обработки запросов чата. */
data class AppConfig(
    val geminiApiKey: String,
    val geminiModel: String,
    val geminiFallbackModels: List<String> = emptyList(),
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val maxContextMessages: Int,
    val frontendHost: String,
    val avatarProvider: AvatarProvider = AvatarProvider.DID,
    val simliApiKey: String? = null,
    val simliFaceId: String? = null,
    val simliTransport: String = "livekit",
    val simliMaxSessionSeconds: Int = 600,
    val simliMaxIdleSeconds: Int = 60,
    val elevenLabsApiKey: String? = null,
    val elevenLabsVoiceId: String? = null,
    val elevenLabsModel: String = "eleven_flash_v2_5",
    val streamMinChars: Int = 50,
) {
    companion object {
        /** Собирает и валидирует runtime-конфигурацию из переменных окружения. */
        fun fromEnvironment(): AppConfig {
            val maxContext = environment("MAX_CONTEXT_MESSAGES", "20").toIntOrNull()
                ?: throw IllegalStateException("MAX_CONTEXT_MESSAGES must be a positive integer")
            require(maxContext > 0) { "MAX_CONTEXT_MESSAGES must be a positive integer" }
            val avatarProvider = AvatarProvider.from(environment("AVATAR_PROVIDER", "did"))
            val simliMaxSessionSeconds = positiveEnvironment("SIMLI_MAX_SESSION_SECONDS", "600")
            val simliMaxIdleSeconds = positiveEnvironment("SIMLI_MAX_IDLE_SECONDS", "60")
            val streamMinChars = positiveEnvironment("STREAM_MIN_CHARS", "50")
            val simliTransport = environment("SIMLI_TRANSPORT", "livekit")
            val simliApiKey = optionalEnvironment("SIMLI_API_KEY")
            val simliFaceId = optionalEnvironment("SIMLI_FACE_ID")
            val elevenLabsApiKey = optionalEnvironment("ELEVENLABS_API_KEY")
            val elevenLabsVoiceId = optionalEnvironment("ELEVENLABS_VOICE_ID")

            if (avatarProvider == AvatarProvider.SIMLI) {
                require(simliTransport == "livekit") { "SIMLI_TRANSPORT must be livekit" }
                requireNotNull(simliApiKey) { "Missing required environment variable: SIMLI_API_KEY" }
                requireNotNull(simliFaceId) { "Missing required environment variable: SIMLI_FACE_ID" }
                requireNotNull(elevenLabsApiKey) { "Missing required environment variable: ELEVENLABS_API_KEY" }
                requireNotNull(elevenLabsVoiceId) { "Missing required environment variable: ELEVENLABS_VOICE_ID" }
            }

            return AppConfig(
                geminiApiKey = requiredEnvironment("GEMINI_API_KEY"),
                geminiModel = environment("GEMINI_MODEL", "gemini-3-flash-preview"),
                geminiFallbackModels = environment("GEMINI_MODEL_FALLBACKS", "gemini-3.6-flash,gemini-3.5-flash-lite")
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty),
                databaseUrl = environment("DATABASE_URL", "jdbc:postgresql://localhost:5432/speaking_character"),
                databaseUser = environment("DATABASE_USER", "speaking_character"),
                databasePassword = environment("DATABASE_PASSWORD", "speaking_character"),
                maxContextMessages = maxContext,
                frontendHost = environment("FRONTEND_HOST", "localhost:8000"),
                avatarProvider = avatarProvider,
                simliApiKey = simliApiKey,
                simliFaceId = simliFaceId,
                simliTransport = simliTransport,
                simliMaxSessionSeconds = simliMaxSessionSeconds,
                simliMaxIdleSeconds = simliMaxIdleSeconds,
                elevenLabsApiKey = elevenLabsApiKey,
                elevenLabsVoiceId = elevenLabsVoiceId,
                elevenLabsModel = environment("ELEVENLABS_MODEL", "eleven_flash_v2_5"),
                streamMinChars = streamMinChars,
            )
        }

        /** Получает необязательную переменную окружения или применяет значение по умолчанию. */
        private fun environment(name: String, default: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

        /** Получает необязательную секретную переменную, не раскрывая её значение. */
        private fun optionalEnvironment(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

        /** Читает положительное целое значение конфигурации. */
        private fun positiveEnvironment(name: String, default: String): Int =
            environment(name, default).toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalStateException("$name must be a positive integer")

        /** Получает обязательный секрет, никогда не включая его значение в текст ошибки. */
        private fun requiredEnvironment(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing required environment variable: $name")
    }
}

/** Допустимые провайдеры аватара: прежний D-ID либо потоковый Simli. */
enum class AvatarProvider {
    DID,
    SIMLI;

    companion object {
        /** Разбирает имя провайдера без зависимости от регистра. */
        fun from(value: String): AvatarProvider = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalStateException("AVATAR_PROVIDER must be either did or simli")
    }
}
