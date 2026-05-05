package xyz.chengzi.backendv2.model

data class CreateSessionRequest(
    val title: String = "New Chat"
)

data class UpdateSessionTitleRequest(
    val title: String
)