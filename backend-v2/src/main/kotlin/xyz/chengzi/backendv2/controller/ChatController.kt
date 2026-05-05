package xyz.chengzi.backendv2.controller

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import xyz.chengzi.backendv2.chat.ChatStorageSelector
import xyz.chengzi.backendv2.chat.ChatSessionDto
import xyz.chengzi.backendv2.model.*
import xyz.chengzi.backendv2.service.CliproxyService

@RestController
@RequestMapping("/api/v1")
class ChatController(
    private val cliproxyService: CliproxyService,
    private val storageSelector: ChatStorageSelector
) {
    private val log = LoggerFactory.getLogger(ChatController::class.java)
    private val storage get() = storageSelector.storage

    @GetMapping("/models")
    fun models(): Map<String, Any> {
        log.info("Fetching available models")
        val cliproxyModels = cliproxyService.getModels()
        return if (cliproxyModels.isNotEmpty()) {
            log.info("Loaded {} models from cliproxy", cliproxyModels.size)
            mapOf("models" to cliproxyModels)
        } else {
            log.info("Using default models list")
            mapOf(
                "models" to listOf(
                    mapOf("id" to "deepseek-v4-flash", "name" to "DeepSeek V4 Flash", "description" to "闪电速度 · 日常首选"),
                    mapOf("id" to "deepseek-v4-pro", "name" to "DeepSeek V4 Pro", "description" to "研究级 · 深度思考"),
                    mapOf("id" to "deepseek-r1", "name" to "DeepSeek R1", "description" to "强推理 · 解谜神器"),
                    mapOf("id" to "qwen3-max", "name" to "通义千问 Max", "description" to "旗舰级 · 全能选手"),
                    mapOf("id" to "qwen3-coder", "name" to "通义千问 Coder", "description" to "代码专家 · 程序员的浪漫"),
                    mapOf("id" to "kimi-k2.6", "name" to "Kimi K2.6", "description" to "超长文本 · 论文神器"),
                    mapOf("id" to "gpt-4o", "name" to "GPT-4o", "description" to "老牌劲旅 · 全能选手"),
                    mapOf("id" to "claude-3.5-sonnet", "name" to "Claude 3.5 Sonnet", "description" to "文艺青年 · 写作助手"),
                    mapOf("id" to "gemini-2.0-flash", "name" to "Gemini 2.0 Flash", "description" to "轻量快速 · 谷歌出品")
                )
            )
        }
    }

    @PostMapping("/chat")
    fun chat(@RequestBody request: ChatRequest): Map<String, Any> {
        val isNewSession = request.sessionId == null
        val sessionId = request.sessionId ?: storage.createSession().id

        log.info("[Chat] SessionId: {}, Model: {}, NewSession: {}, MessageCount: {}",
            sessionId, request.model, isNewSession, request.messages.size)

        request.messages.forEach { msg ->
            storage.addMessage(sessionId, msg.role, msg.content, request.model)
            log.debug("[Chat] Saved {} message: {}", msg.role, msg.content.take(50))
        }

        // Auto-update session title from first user message
        if (isNewSession && request.messages.isNotEmpty()) {
            val firstUserMessage = request.messages.firstOrNull { it.role == "user" }
            if (firstUserMessage != null) {
                val title = if (firstUserMessage.content.length > 30) {
                    firstUserMessage.content.substring(0, 30) + "..."
                } else {
                    firstUserMessage.content
                }
                storage.updateSessionTitle(sessionId, title)
                log.info("[Chat] New session titled: {}", title)
            }
        }

        log.info("[Chat] Calling cliproxy API...")
        val response = cliproxyService.chat(request.model, request.messages)

        @Suppress("UNCHECKED_CAST")
        val assistantMessage: String? = try {
            val choices = response["choices"] as? List<Map<String, Any>>
            val firstChoice = choices?.firstOrNull()
            val message = firstChoice?.get("message") as? Map<String, Any>
            message?.get("content") as? String
        } catch (e: Exception) {
            log.error("[Chat] Failed to parse cliproxy response", e)
            null
        }

        if (assistantMessage != null) {
            storage.addMessage(sessionId, "assistant", assistantMessage, request.model)
            log.info("[Chat] Response saved, length: {}", assistantMessage.length)
        }

        return mapOf(
            "sessionId" to sessionId,
            "choices" to listOf(mapOf("message" to mapOf("content" to (assistantMessage ?: "响应为空"))))
        )
    }

    @PostMapping("/sessions")
    fun createSession(@RequestBody request: CreateSessionRequest): ChatSessionDto {
        log.info("[Session] Creating new session: {}", request.title)
        return storage.createSession(request.title)
    }

    @GetMapping("/sessions")
    fun listSessions(): List<ChatSessionDto> {
        log.info("[Session] Listing all sessions")
        return storage.getAllSessions()
    }

    @GetMapping("/sessions/{sessionId}")
    fun getSession(@PathVariable sessionId: String): ChatSessionDto? {
        log.info("[Session] Getting session: {}", sessionId)
        return storage.getSession(sessionId)
    }

    @DeleteMapping("/sessions/{sessionId}")
    fun deleteSession(@PathVariable sessionId: String) {
        log.info("[Session] Deleting session: {}", sessionId)
        storage.deleteSession(sessionId)
    }

    @PatchMapping("/sessions/{sessionId}/title")
    fun updateSessionTitle(
        @PathVariable sessionId: String,
        @RequestBody request: UpdateSessionTitleRequest
    ) {
        log.info("[Session] Updating session {} title to: {}", sessionId, request.title)
        storage.updateSessionTitle(sessionId, request.title)
    }
}