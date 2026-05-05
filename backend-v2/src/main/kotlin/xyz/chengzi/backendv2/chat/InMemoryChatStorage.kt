package xyz.chengzi.backendv2.chat

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class InMemoryChatStorage : ChatStorage {
    private val sessions = mutableMapOf<String, InMemorySession>()
    private val messages = mutableMapOf<String, MutableList<InMemoryMessage>>()

    override fun createSession(title: String): ChatSessionDto {
        val session = InMemorySession(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        sessions[session.id] = session
        messages[session.id] = mutableListOf()
        return session.toDto(emptyList())
    }

    override fun getSession(sessionId: String): ChatSessionDto? {
        return sessions[sessionId]?.toDto(messages[sessionId]?.map { it.toDto() } ?: emptyList())
    }

    override fun getAllSessions(): List<ChatSessionDto> {
        return sessions.values.map { session ->
            session.toDto(messages[session.id]?.map { it.toDto() } ?: emptyList())
        }.sortedByDescending { it.updatedAt }
    }

    override fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        messages.remove(sessionId)
    }

    override fun updateSessionTitle(sessionId: String, title: String) {
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(title = title, updatedAt = Instant.now())
        }
    }

    override fun addMessage(sessionId: String, role: String, content: String, model: String?): ChatMessageDto {
        val message = InMemoryMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            content = content,
            model = model,
            createdAt = Instant.now()
        )
        messages.getOrPut(sessionId) { mutableListOf() }.add(message)
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(updatedAt = Instant.now())
        }
        return message.toDto()
    }

    override fun getMessages(sessionId: String): List<ChatMessageDto> {
        return messages[sessionId]?.map { it.toDto() } ?: emptyList()
    }

    private data class InMemorySession(
        val id: String,
        val title: String,
        val createdAt: Instant,
        val updatedAt: Instant
    ) {
        fun toDto(msgs: List<ChatMessageDto>) = ChatSessionDto(
            id = id,
            title = title,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
            messages = msgs
        )
    }

    private data class InMemoryMessage(
        val id: String,
        val role: String,
        val content: String,
        val model: String? = null,
        val createdAt: Instant
    ) {
        fun toDto() = ChatMessageDto(
            id = id,
            role = role,
            content = content,
            model = model,
            createdAt = createdAt.toString()
        )
    }
}