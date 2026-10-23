package com.piko.agent.data

sealed interface MemoryCommand {
    data class Remember(val key: String, val content: String) : MemoryCommand
    data class Forget(val key: String) : MemoryCommand

    suspend fun execute(repository: MemoryRepository): String = when (this) {
        is Remember -> {
            repository.save(key, content)
            "Memory saved locally. Manage it in Assistant → Memory. Relevant memories are included with model requests."
        }
        is Forget -> if (repository.forget(key)) "Memory deleted." else "No memory matched that key. Open Assistant → Memory."
    }
    companion object {
        fun parse(text: String): MemoryCommand? {
            val remember = Regex("(?i)^remember(?: that)?\\s+(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(text.trim())
            if (remember != null) {
                val value = remember.groupValues[1]
                val parts = value.split("=", limit = 2)
                return Remember(if (parts.size == 2) parts[0].trim() else value.take(120), if (parts.size == 2) parts[1].trim() else value)
            }
            return Regex("(?i)^forget\\s+(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(text.trim())?.let { Forget(it.groupValues[1].trim()) }
        }
    }
}
