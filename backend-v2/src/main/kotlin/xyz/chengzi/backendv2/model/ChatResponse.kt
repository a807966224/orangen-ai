package xyz.chengzi.backendv2.model

data class ChatResponse(
    val choices: List<Choice>? = null,
    val error: String? = null
)

data class Choice(
    val message: ResponseMessage
)

data class ResponseMessage(
    val content: String?
)
