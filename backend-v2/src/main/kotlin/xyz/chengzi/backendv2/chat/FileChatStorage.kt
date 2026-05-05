package xyz.chengzi.backendv2.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

@Component
class FileChatStorage(
    private val dataDir: Path = Paths.get(System.getProperty("user.dir"), "data", "chat")
) : ChatStorage {

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val sessionFile: Path = dataDir.resolve("sessions.json")
    private val messageFile: Path = dataDir.resolve("messages.json")

    private data class SessionData(val id: String, val title: String, val createdAt: String, val updatedAt: String)
    private data class MessageData(val id: String, val sessionId: String, val role: String, val content: String, val model: String?, val createdAt: String)

    private val sessions = mutableMapOf<String, SessionData>()
    private val messages = mutableMapOf<String, MutableList<MessageData>>()

    init {
        Files.createDirectories(dataDir)
        loadFromDisk()
    }

    private fun loadFromDisk() {
        try {
            if (Files.exists(sessionFile)) {
                val json = Files.readString(sessionFile)
                val data: List<SessionData> = objectMapper.readValue(json)
                sessions.clear()
                sessions.putAll(data.associateBy { it.id })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            if (Files.exists(messageFile)) {
                val json = Files.readString(messageFile)
                val data: List<MessageData> = objectMapper.readValue(json)
                messages.clear()
                data.groupByTo(messages) { it.sessionId }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveSessions() {
        try {
            objectMapper.writeValue(sessionFile.toFile(), sessions.values.toList())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveMessages() {
        try {
            objectMapper.writeValue(messageFile.toFile(), messages.values.flatten())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun createSession(title: String): ChatSessionDto {
        val now = Instant.now().toString()
        val session = SessionData(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now
        )
        sessions[session.id] = session
        messages[session.id] = mutableListOf()
        saveSessions()
        return session.toDto(emptyList())
    }

    override fun getSession(sessionId: String): ChatSessionDto? {
        val session = sessions[sessionId] ?: return null
        val msgs = messages[sessionId]?.map { it.toDto() } ?: emptyList()
        return session.toDto(msgs)
    }

    override fun getAllSessions(): List<ChatSessionDto> {
        return sessions.values.map { session ->
            val msgs = messages[session.id]?.map { it.toDto() } ?: emptyList()
            session.toDto(msgs)
        }.sortedByDescending { it.updatedAt }
    }

    override fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        messages.remove(sessionId)
        saveSessions()
        saveMessages()
    }

    override fun updateSessionTitle(sessionId: String, title: String) {
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(title = title, updatedAt = Instant.now().toString())
            saveSessions()
        }
    }

    override fun addMessage(sessionId: String, role: String, content: String, model: String?): ChatMessageDto {
        val message = MessageData(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role,
            content = content,
            model = model,
            createdAt = Instant.now().toString()
        )
        messages.getOrPut(sessionId) { mutableListOf() }.add(message)
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(updatedAt = Instant.now().toString())
        }
        saveSessions()
        saveMessages()
        return message.toDto()
    }

    override fun getMessages(sessionId: String): List<ChatMessageDto> {
        return messages[sessionId]?.map { it.toDto() } ?: emptyList()
    }

    private fun SessionData.toDto(msgs: List<ChatMessageDto>) = ChatSessionDto(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        messages = msgs
    )

    private fun MessageData.toDto() = ChatMessageDto(
        id = id,
        role = role,
        content = content,
        model = model,
        createdAt = createdAt
    )
}