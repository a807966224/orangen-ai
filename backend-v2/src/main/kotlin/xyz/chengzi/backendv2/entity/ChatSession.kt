package xyz.chengzi.backendv2.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "chat_sessions")
data class ChatSession(
    @Id
    val id: String = UUID.randomUUID().toString(),

    var title: String = "New Chat",

    @Column(name = "created_at")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now(),

    @OneToMany(mappedBy = "sessionId", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("createdAt ASC")
    val messages: MutableList<ChatMessage> = mutableListOf()
)