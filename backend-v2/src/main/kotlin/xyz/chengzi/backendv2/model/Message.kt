package xyz.chengzi.backendv2.model

/**
 * Content part - can be text or image
 */
sealed class ContentPart {
    data class Text(val text: String) : ContentPart()
    data class ImageUrl(val url: String, val detail: String = "low") : ContentPart()
}

data class Message(
    val role: String,
    val content: String,
    val id: String? = null
) {
    /**
     * Convert to API format - returns either a String for text-only,
     * or a List for multimodal content
     */
    fun toApiContent(): Any {
        // Check if content looks like a base64 image
        return if (content.startsWith("data:image")) {
            // It's a base64 image, create image URL content
            listOf(
                mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf(
                        "url" to content,
                        "detail" to "low"
                    )
                ),
                mapOf(
                    "type" to "text",
                    "text" to ""
                )
            )
        } else {
            content
        }
    }
}