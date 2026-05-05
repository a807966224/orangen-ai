package xyz.chengzi.backendv2.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import xyz.chengzi.backendv2.config.CliproxyConfig
import xyz.chengzi.backendv2.model.Message

@Service
class CliproxyService(
    private val cliproxyConfig: CliproxyConfig
) {
    private val log = LoggerFactory.getLogger(CliproxyService::class.java)

    private val restClient = RestClient.builder()
        .baseUrl(cliproxyConfig.apiUrl)
        .defaultHeader("Authorization", "Bearer ${cliproxyConfig.apiKey}")
        .defaultHeader("Content-Type", "application/json")
        .build()

    fun chat(model: String, messages: List<Message>): Map<String, Any> {
        val messageMaps: List<Map<String, Any>> = messages.map { msg ->
            val content = msg.toApiContent()
            when (content) {
                is String -> mapOf("role" to msg.role, "content" to content)
                is List<*> -> mapOf("role" to msg.role, "content" to content)
                else -> mapOf("role" to msg.role, "content" to content.toString())
            }
        }
        val request = mapOf("model" to model, "messages" to messageMaps)

        log.debug("[Cliproxy] Request: model={}, messages={}", model, messageMaps.size)

        return restClient.post()
            .uri("/v1/chat/completions")
            .body(request)
            .retrieve()
            .body(Map::class.java) as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    fun getModels(): List<Map<String, String>> {
        return try {
            val response = restClient.get()
                .uri("/v1/models")
                .retrieve()
                .body(Map::class.java) as Map<String, Any>
            (response["data"] as? List<Map<String, String>>) ?: emptyList()
        } catch (e: Exception) {
            log.warn("[Cliproxy] Failed to get models from cliproxy: {}", e.message)
            emptyList()
        }
    }
}
