package xyz.chengzi.backendv2.model

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val sessionId: String? = null
)