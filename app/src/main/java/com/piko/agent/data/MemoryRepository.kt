package com.piko.agent.data

import com.piko.agent.core.SensitiveTextRedactor
import com.piko.agent.data.db.*
import java.util.UUID
import java.util.Locale

class MemoryRepository(private val dao: AssistantDao) {
    val memories = dao.memories()
    suspend fun save(key: String, content: String, category: String = "Preference", approved: Boolean = true, expiresAt: Long? = null, source: String = "Explicit user input"): MemoryEntity {
        require(key.isNotBlank() && key.length <= 120) { "Provide a memory key of 1–120 characters." }
        require(isSafe(content)) { "Memory contains credential-like or private information. Store only non-sensitive preferences." }
        require(expiresAt == null || expiresAt > System.currentTimeMillis()) { "Expiration must be in the future." }
        val normalized = key.trim().lowercase(Locale.ROOT)
        val previous = dao.memoryByKey(normalized)
        require(approved || previous?.approved != true) { "An approved preference already uses this key. Edit it in Memory." }
        val now = System.currentTimeMillis()
        val memory = MemoryEntity(previous?.id ?: UUID.randomUUID().toString(), normalized, content.trim(), category.take(64), previous?.createdAt ?: now, now, expiresAt, source = source, approved = approved)
        dao.putMemory(memory)
        return memory
    }
    suspend fun delete(id: String) = dao.deleteMemory(id)
    suspend fun clear() = dao.clearMemories()
    suspend fun forget(key: String): Boolean {
        val memory = dao.memoryByKey(key.trim().lowercase(Locale.ROOT)) ?: return false
        dao.deleteMemory(memory.id); return true
    }
    suspend fun relevant(instruction: String): List<MemoryEntity> = rank(dao.candidates(System.currentTimeMillis()), instruction)

    companion object {
        fun isSafe(content: String): Boolean = content.isNotBlank() && content.length <= 1000 &&
            !Regex("(?i)password|passwd|passcode|\\bpin\\b|\\botp\\b|token|secret|api[ _-]?key|credit.card|bank.account|private.message|recovery.code").containsMatchIn(content) &&
            !Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)|[A-Za-z0-9_+/=-]{40,}").containsMatchIn(content) &&
            SensitiveTextRedactor.redactVisibleText(content) == content
        fun rank(candidates: List<MemoryEntity>, instruction: String): List<MemoryEntity> {
            val terms = instruction.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 2 }.toSet()
            return candidates.filter { it.approved && (it.expiresAt == null || it.expiresAt > System.currentTimeMillis()) }
                .map { memory -> memory to terms.count { it in (memory.memoryKey + " " + memory.content + " " + memory.category).lowercase(Locale.ROOT) } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(5).map { it.first }
        }
    }
}
