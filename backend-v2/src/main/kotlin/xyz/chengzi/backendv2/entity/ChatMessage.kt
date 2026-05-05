package xyz.chengzi.backendv2.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "chat_messages")
data class ChatMessage(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(name = "session_id")
    val sessionId: String,

    val role: String,

    val content: String,

    val model: String? = null,

    @Column(name = "created_at")
    val createdAt: Instant = Instant.now()
)