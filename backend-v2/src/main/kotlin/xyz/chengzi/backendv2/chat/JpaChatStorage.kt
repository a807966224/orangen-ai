package xyz.chengzi.backendv2.chat

import org.springframework.stereotype.Component
import xyz.chengzi.backendv2.entity.ChatMessage
import xyz.chengzi.backendv2.entity.ChatSession
import xyz.chengzi.backendv2.repository.ChatMessageRepository
import xyz.chengzi.backendv2.repository.ChatSessionRepository

@Component
class JpaChatStorage(
    private val sessionRepository: ChatSessionRepository,
    private val messageRepository: ChatMessageRepository
) : ChatStorage {

    override fun createSession(title: String): ChatSessionDto {
        val session = ChatSession(title = title)
        return sessionRepository.save(session).toDto(emptyList())
    }

    override fun getSession(sessionId: String): ChatSessionDto? {
        return sessionRepository.findById(sessionId).orElse(null)?.toDto(getMessages(sessionId))
    }

    override fun getAllSessions(): List<ChatSessionDto> {
        return sessionRepository.findAll()
            .map { it.toDto(emptyList()) }
            .sortedByDescending { it.updatedAt }
    }

    override fun deleteSession(sessionId: String) {
        sessionRepository.deleteById(sessionId)
    }

    override fun updateSessionTitle(sessionId: String, title: String) {
        sessionRepository.findById(sessionId).ifPresent { session ->
            session.title = title
            session.updatedAt = java.time.Instant.now()
            sessionRepository.save(session)
        }
    }

    override fun addMessage(sessionId: String, role: String, content: String, model: String?): ChatMessageDto {
        val message = ChatMessage(
            sessionId = sessionId,
            role = role,
            content = content,
            model = model
        )
        val saved = messageRepository.save(message)
        sessionRepository.findById(sessionId).ifPresent { session ->
            session.updatedAt = java.time.Instant.now()
            sessionRepository.save(session)
        }
        return saved.toDto()
    }

    override fun getMessages(sessionId: String): List<ChatMessageDto> {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).map { it.toDto() }
    }

    private fun ChatSession.toDto(messages: List<ChatMessageDto>) = ChatSessionDto(
        id = id,
        title = title,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        messages = messages
    )

    private fun ChatMessage.toDto() = ChatMessageDto(
        id = id,
        role = role,
        content = content,
        model = model,
        createdAt = createdAt.toString()
    )
}