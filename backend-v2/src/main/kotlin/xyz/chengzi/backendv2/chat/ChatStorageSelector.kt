package xyz.chengzi.backendv2.chat

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ChatStorageSelector(
    private val fileChatStorage: FileChatStorage,
    private val jpaChatStorage: JpaChatStorage,
    @Value("\${app.storage-type:FILE}")
    private val storageType: String
) {
    val storage: ChatStorage
        get() = when (storageType.uppercase()) {
            "POSTGRES", "JPA" -> jpaChatStorage
            else -> fileChatStorage
        }
}