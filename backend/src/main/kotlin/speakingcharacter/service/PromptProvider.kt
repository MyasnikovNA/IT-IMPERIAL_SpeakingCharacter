/** Загружает неизменяемые prompt-ресурсы один раз при запуске приложения. */
package speakingcharacter.service

/** Предоставляет prompts для диалога и итоговой оценки без обращения к файлам в сервисах. */
class PromptProvider {
    private val systemPrompt = loadPrompt("prompts/demo_system_prompt.txt")

    /** Возвращает существующий system prompt персонажа. */
    fun getSystemPrompt(): String = systemPrompt

    /** Читает обязательный непустой prompt из classpath. */
    private fun loadPrompt(path: String): String = PromptProvider::class.java.classLoader
        .getResourceAsStream(path)
        ?.bufferedReader()
        ?.use { it.readText().trim() }
        ?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("Prompt resource is missing or empty: $path")
}
