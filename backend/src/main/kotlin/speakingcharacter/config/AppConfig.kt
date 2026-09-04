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
) {
    companion object {
        /** Собирает и валидирует runtime-конфигурацию из переменных окружения. */
        fun fromEnvironment(): AppConfig {
            val maxContext = environment("MAX_CONTEXT_MESSAGES", "20").toIntOrNull()
                ?: throw IllegalStateException("MAX_CONTEXT_MESSAGES must be a positive integer")
            require(maxContext > 0) { "MAX_CONTEXT_MESSAGES must be a positive integer" }

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
            )
        }

        /** Получает необязательную переменную окружения или применяет значение по умолчанию. */
        private fun environment(name: String, default: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

        /** Получает обязательный секрет, никогда не включая его значение в текст ошибки. */
        private fun requiredEnvironment(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing required environment variable: $name")
    }
}
