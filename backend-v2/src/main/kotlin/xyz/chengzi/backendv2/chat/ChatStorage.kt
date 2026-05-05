package xyz.chengzi.backendv2.chat

import xyz.chengzi.backendv2.model.Message

interface ChatStorage {
    fun createSession(title: String = "New Chat"): ChatSessionDto
    fun getSession(sessionId: String): ChatSessionDto?
    fun getAllSessions(): List<ChatSessionDto>
    fun deleteSession(sessionId: String)
    fun updateSessionTitle(sessionId: String, title: String)

    fun addMessage(sessionId: String, role: String, content: String, model: String? = null): ChatMessageDto
    fun getMessages(sessionId: String): List<ChatMessageDto>
}

data class ChatSessionDto(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val messages: List<ChatMessageDto> = emptyList()
)

data class ChatMessageDto(
    val id: String,
    val role: String,
    val content: String,
    val model: String? = null,
    val createdAt: String
)