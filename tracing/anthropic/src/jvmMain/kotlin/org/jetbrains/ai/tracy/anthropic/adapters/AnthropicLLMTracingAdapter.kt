/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.media.*
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.ContentKind
import org.jetbrains.ai.tracy.core.policy.contentTracingAllowed
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*
import kotlinx.serialization.json.*
import mu.KotlinLogging

/**
 * Tracing adapter for Anthropic Claude API.
 *
 * Parses Anthropic Messages API requests and responses to extract telemetry data including
 * model parameters, messages, tool definitions, tool calls, usage statistics, and media content.
 * Supports both text and multimodal inputs (images, documents), extended thinking, streaming,
 * batches, files, and models APIs.
 *
 * ## Example Usage
 * ```kotlin
 * val client = instrument(HttpClient(), AnthropicLLMTracingAdapter())
 * client.post("https://api.anthropic.com/v1/messages") {
 *     header("x-api-key", apiKey)
 *     header("anthropic-version", "2023-06-01")
 *     setBody("""
 *         {
 *             "max_tokens": 1024,
 *             "messages": [{"content": "Hello!", "role": "user"}],
 *             "model": "claude-3-7-sonnet-latest"
 *         }
 *     """)
 * }
 * // Automatically traces request/response with tool calls and media content
 * ```
 *
 * See: [Anthropic Messages API](https://docs.claude.com/en/api/messages)
 */
class AnthropicLLMTracingAdapter : LLMTracingAdapter(genAISystem = GenAiSystemIncubatingValues.ANTHROPIC) {
    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {
        val apiType = detectApiType(request.url)
        val operationName = detectOperationName(request.url, request.method)

        if (apiType != null) span.setAttribute("anthropic.api.type", apiType)
        if (operationName != null) span.setAttribute("gen_ai.operation.name", operationName)

        when (apiType) {
            "messages", "count_tokens", null -> handleMessagesRequest(span, request)
            "batches" -> handleBatchesRequest(span, request)
            "files" -> handleFilesRequest(span, request)
            "models" -> handleModelsRequest(span, request)
        }
    }

    private fun handleMessagesRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["temperature"]?.jsonPrimitive?.doubleOrNull?.let { span.setAttribute(GEN_AI_REQUEST_TEMPERATURE, it) }
        body["model"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.content) }
        body["max_tokens"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute(GEN_AI_REQUEST_MAX_TOKENS, it.toLong()) }

        // stop sequences
        body["stop_sequences"]?.let {
            if (it is JsonArray) {
                span.setAttribute(STOP_SEQUENCES_KEY, it.map { s -> s.jsonPrimitive.content })
            }
        }

        // thinking parameters
        body["thinking"]?.jsonObject?.let { thinking ->
            thinking["type"]?.jsonPrimitive?.let { span.setAttribute("gen_ai.request.thinking.type", it.content) }
            thinking["budget_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.request.thinking.budget_tokens", it.toLong())
            }
        }

        // metadata
        body["metadata"]?.jsonObject?.let { metadata ->
            metadata["user_id"]?.jsonPrimitive?.let { span.setAttribute("gen_ai.metadata.user_id", it.content) }
        }
        body["service_tier"]?.jsonPrimitive?.let {
            span.setAttribute("gen_ai.usage.service_tier", it.content)
        }

        // system prompt
        when (val system = body["system"]) {
            is JsonPrimitive -> {
                span.setAttribute("gen_ai.prompt.system.content", system.content.orRedactedInput())
            }
            is JsonArray -> {
                for ((index, block) in system.withIndex()) {
                    block.jsonObject["type"]?.jsonPrimitive?.content?.let {
                        span.setAttribute("gen_ai.prompt.system.$index.type", it)
                    }
                    block.jsonObject["text"]?.jsonPrimitive?.content?.let {
                        span.setAttribute("gen_ai.prompt.system.$index.content", it.orRedactedInput())
                    }
                }
            }
            else -> {}
        }

        body["top_k"]?.jsonPrimitive?.doubleOrNull?.let { span.setAttribute(GEN_AI_REQUEST_TOP_K, it) }
        body["top_p"]?.jsonPrimitive?.doubleOrNull?.let { span.setAttribute(GEN_AI_REQUEST_TOP_P, it) }

        body["messages"]?.let {
            if (it is JsonArray) {
                for ((index, message) in it.jsonArray.withIndex()) {
                    span.setAttribute("gen_ai.prompt.$index.role", message.jsonObject["role"]?.jsonPrimitive?.content)
                    val content = message.jsonObject["content"]?.toString()
                    // treat all request messages (including assistant history) as input per policy
                    span.setAttribute("gen_ai.prompt.$index.content", content?.orRedactedInput())
                }
            }
        }

        // extracting definitions of tool calls
        // see: https://docs.anthropic.com/en/api/messages#body-tools
        body["tools"]?.let {
            if (it is JsonArray) {
                for ((index, tool) in it.jsonArray.withIndex()) {
                    val name = tool.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    val description = tool.jsonObject["description"]?.jsonPrimitive?.contentOrNull
                    // Anthropic tools default to "custom" type when not specified
                    val type = tool.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "custom"
                    val parameters = tool.jsonObject["input_schema"]?.toString()

                    span.setAttribute("gen_ai.tool.$index.name", name?.orRedactedInput())
                    span.setAttribute("gen_ai.tool.$index.description", description?.orRedactedInput())
                    span.setAttribute("gen_ai.tool.$index.type", type)
                    span.setAttribute("gen_ai.tool.$index.parameters", parameters?.orRedactedInput())
                }
            }
        }

        if (contentTracingAllowed(ContentKind.INPUT)) {
            val mediaContent = parseMediaContent(body)
            if (mediaContent != null) {
                extractor.setUploadableContentAttributes(span, field = "input", mediaContent)
            }
        }

        span.populateUnmappedAttributes(body, mappedMessagesRequestAttributes, PayloadType.REQUEST)
    }

    private fun handleBatchesRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        body["requests"]?.let {
            if (it is JsonArray) {
                span.setAttribute("gen_ai.request.batch.size", it.size.toLong())
            }
        }
    }

    private fun handleFilesRequest(span: Span, request: TracyHttpRequest) {
        val formData = request.body.asFormData() ?: return
        val filePart = formData.parts.firstOrNull { it.name == "file" } ?: return
        filePart.filename?.let { span.setAttribute("gen_ai.request.file.filename", it) }
        filePart.contentType?.mimeType?.let { span.setAttribute("gen_ai.request.file.mime_type", it) }
        span.setAttribute("gen_ai.request.file.size_bytes", filePart.content.size.toLong())
    }

    private fun handleModelsRequest(span: Span, request: TracyHttpRequest) {
        // For models.retrieve, extract model ID from URL path: /v1/models/{id}
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        val apiSegments = if (segments.firstOrNull() == "v1") segments.drop(1) else segments
        if (apiSegments.size >= 2 && apiSegments[0] == "models") {
            span.setAttribute(GEN_AI_REQUEST_MODEL, apiSegments[1])
        }
    }

    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val apiType = detectApiType(response.url)

        when (apiType) {
            "count_tokens" -> handleCountTokensResponse(span, body, response)
            "batches" -> handleBatchesResponse(span, body)
            "files" -> handleFilesResponse(span, body)
            "models" -> handleModelsResponse(span, body)
            else -> handleMessagesResponse(span, body)
        }
    }

    private fun handleMessagesResponse(span: Span, body: JsonObject) {
        body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
        body["type"]?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it.jsonPrimitive.content) }
        body["role"]?.let { span.setAttribute("gen_ai.response.role", it.jsonPrimitive.content) }
        body["model"]?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it.jsonPrimitive.content) }

        // collecting response messages
        val contentArray = body["content"] as? JsonArray
        if (!contentArray.isNullOrEmpty()) {
            for ((index, message) in contentArray.withIndex()) {
                val type = message.jsonObject["type"]?.jsonPrimitive?.content
                span.setAttribute("gen_ai.completion.$index.type", type)

                when (type) {
                    "text" -> {
                        // normal text message
                        val text = message.jsonObject["text"]?.jsonPrimitive?.content
                        span.setAttribute(
                            "gen_ai.completion.$index.content",
                            if (text.isNullOrBlank()) "(empty)" else text.orRedactedOutput()
                        )
                    }

                    "thinking" -> {
                        // extended thinking block
                        val thinkingText = message.jsonObject["thinking"]?.jsonPrimitive?.content
                        span.setAttribute(
                            "gen_ai.completion.$index.thinking",
                            thinkingText?.orRedactedOutput()
                        )
                        span.setAttribute(
                            "gen_ai.completion.$index.content",
                            thinkingText?.orRedactedOutput()
                        )
                    }

                    "tool_use" -> {
                        // tool call request by LLM
                        val toolCall = message
                        span.setAttribute(
                            "gen_ai.completion.$index.tool.call.id",
                            toolCall.jsonObject["id"]?.jsonPrimitive?.content
                        )
                        span.setAttribute(
                            "gen_ai.completion.$index.tool.call.type",
                            toolCall.jsonObject["type"]?.jsonPrimitive?.content
                        )
                        span.setAttribute(
                            "gen_ai.completion.$index.tool.name",
                            toolCall.jsonObject["name"]?.jsonPrimitive?.content?.orRedactedOutput()
                        )
                        val inputJson = toolCall.jsonObject["input"]?.toString()
                        span.setAttribute(
                            "gen_ai.completion.$index.tool.arguments",
                            inputJson?.orRedactedOutput()
                        )
                        // Set content to tool input JSON so gen_ai.completion.N.content is always non-empty
                        span.setAttribute(
                            "gen_ai.completion.$index.content",
                            inputJson?.orRedactedOutput()
                        )
                    }

                    else -> {
                        span.setAttribute("gen_ai.completion.$index.content", message.toString().orRedactedOutput())
                    }
                }
            }
        } else {
            // Empty content array: model produced no visible output (e.g. end_turn with 0 tokens).
            // Record a minimal completion entry so gen_ai.completion.0.content is always present.
            val stopReason = body["stop_reason"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            if (stopReason != null) {
                span.setAttribute("gen_ai.completion.0.type", "text")
                span.setAttribute("gen_ai.completion.0.content", "(empty: $stopReason)")
            }
        }

        // finish reason
        body["stop_reason"]?.let {
            span.setAttribute(GEN_AI_RESPONSE_FINISH_REASONS, listOf(it.jsonPrimitive.content))
        }

        // collecting usage stats (e.g., input/output tokens)
        body["usage"]?.jsonObject?.let { usage ->
            usage["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
            }
            usage["output_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it)
            }
            usage["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.usage.cache_creation.input_tokens", it.toLong())
            }
            usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.usage.cache_read.input_tokens", it.toLong())
            }
            usage["service_tier"]?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.usage.service_tier", it.content)
            }
        }

        span.populateUnmappedAttributes(body, mappedMessagesResponseAttributes, PayloadType.RESPONSE)
    }

    private fun handleCountTokensResponse(span: Span, body: JsonObject, response: TracyHttpResponse) {
        // Check body id first, then common request-id response headers, finally fall back to span id.
        val headerCandidates = listOf("request-id", "x-request-id", "anthropic-request-id", "x-litellm-request-id")
        val id = body["id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            ?: headerCandidates.firstNotNullOfOrNull { name ->
                response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
            }
            ?: span.spanContext.spanId
        if (!id.isNullOrBlank()) span.setAttribute(GEN_AI_RESPONSE_ID, id)
        body["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
        }
    }

    private fun handleBatchesResponse(span: Span, body: JsonObject) {
        if (body.containsKey("data")) {
            // List response
            val data = body["data"]?.jsonArray
            span.setAttribute("gen_ai.response.list.count", (data?.size ?: 0).toLong())
            val hasMore = body["has_more"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: "false"
            span.setAttribute("gen_ai.response.list.has_more", hasMore)
            val firstId = body["first_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                ?: data?.firstOrNull()?.jsonObject?.get("id")?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            if (firstId != null) span.setAttribute("gen_ai.response.list.first_id", firstId)
            val lastId = body["last_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                ?: data?.lastOrNull()?.jsonObject?.get("id")?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            if (lastId != null) span.setAttribute("gen_ai.response.list.last_id", lastId)
        } else {
            // Single batch response (create, retrieve, cancel, delete)
            body["id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.response.batch.id", it.content)
            }
            body["type"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute(GEN_AI_OUTPUT_TYPE, it.content)
            }
            body["processing_status"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.response.batch.processing_status", it.content)
            }
            body["created_at"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.response.batch.created_at", it.content)
            }
            body["expires_at"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.response.batch.expires_at", it.content)
            }
            body["request_counts"]?.jsonObject?.let { counts ->
                counts["processing"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.batch.request_counts.processing", it.toLong())
                }
                counts["succeeded"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.batch.request_counts.succeeded", it.toLong())
                }
                counts["errored"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.batch.request_counts.errored", it.toLong())
                }
                counts["canceled"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.batch.request_counts.canceled", it.toLong())
                }
                counts["expired"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.batch.request_counts.expired", it.toLong())
                }
            }
        }
    }

    private fun handleFilesResponse(span: Span, body: JsonObject) {
        if (body.containsKey("data")) {
            // List response
            val data = body["data"]?.jsonArray
            span.setAttribute("gen_ai.response.list.count", (data?.size ?: 0).toLong())
            val hasMore = body["has_more"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: "false"
            span.setAttribute("gen_ai.response.list.has_more", hasMore)
            val firstId = body["first_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                ?: data?.firstOrNull()?.jsonObject?.get("id")?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            if (firstId != null) span.setAttribute("gen_ai.response.list.first_id", firstId)
            val lastId = body["last_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                ?: data?.lastOrNull()?.jsonObject?.get("id")?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            if (lastId != null) span.setAttribute("gen_ai.response.list.last_id", lastId)
        } else {
            // Single file response (create, retrieve, delete)
            val isDeleted = body["deleted"]?.jsonPrimitive?.boolean == true
            val fileType = if (isDeleted) "file_deleted" else body["type"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            if (fileType != null) span.setAttribute(GEN_AI_OUTPUT_TYPE, fileType)

            body["id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.response.file.id", it.content)
            }
            body["filename"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.response.file.filename", it.content)
            }
            body["mime_type"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.response.file.mime_type", it.content)
            }
            body["size"]?.takeIf { it != JsonNull }?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.response.file.size_bytes", it.toLong())
            }
            body["downloadable"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.response.file.downloadable", it.content)
            }
            body["created_at"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.response.file.created_at", it.content)
            }
        }
    }

    private fun handleModelsResponse(span: Span, body: JsonObject) {
        if (body.containsKey("data")) {
            // List response
            val data = body["data"]?.jsonArray
            span.setAttribute("gen_ai.response.list.count", (data?.size ?: 0).toLong())
            // has_more: use explicit field or default to "false" if not provided by proxy
            val hasMore = body["has_more"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: "false"
            span.setAttribute("gen_ai.response.list.has_more", hasMore)
            // first_id/last_id: use explicit field or synthesize from first/last data item
            val firstId = body["first_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                ?: data?.firstOrNull()?.jsonObject?.get("id")?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            if (firstId != null) span.setAttribute("gen_ai.response.list.first_id", firstId)
            val lastId = body["last_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                ?: data?.lastOrNull()?.jsonObject?.get("id")?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            if (lastId != null) span.setAttribute("gen_ai.response.list.last_id", lastId)
        } else {
            // Single model response (retrieve)
            body["type"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute(GEN_AI_OUTPUT_TYPE, it.content)
            }
            body["id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute(GEN_AI_RESPONSE_MODEL, it.content)
                span.setAttribute("gen_ai.response.model.id", it.content)
            }
            body["display_name"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.response.model.display_name", it.content)
            }
            body["created_at"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                span.setAttribute("gen_ai.response.model.created_at", it.content)
            }
            body["max_input_tokens"]?.takeIf { it != JsonNull }?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.response.model.max_input_tokens", it.toLong())
            }
            body["max_output_tokens"]?.takeIf { it != JsonNull }?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.response.model.max_output_tokens", it.toLong())
            }
            body["capabilities"]?.jsonObject?.let { caps ->
                caps["vision"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                    span.setAttribute("gen_ai.response.model.capabilities.vision", it.content)
                }
                caps["citations"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                    span.setAttribute("gen_ai.response.model.capabilities.citations", it.content)
                }
                caps["batch"]?.takeIf { it != JsonNull }?.jsonPrimitive?.let {
                    span.setAttribute("gen_ai.response.model.capabilities.batch", it.content)
                }
            }
        }
    }

    override fun getSpanName(request: TracyHttpRequest) = "Anthropic-generation"

    override fun isStreamingRequest(request: TracyHttpRequest): Boolean {
        return request.body.asJson()?.jsonObject?.get("stream")?.jsonPrimitive?.boolean == true
    }

    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {
        events.lines().forEach { line ->
            if (!line.startsWith("data: ")) return@forEach
            val data = line.removePrefix("data: ")
            if (data == "[DONE]") return@forEach
            runCatching {
                val event = Json.parseToJsonElement(data).jsonObject
                when (event["type"]?.jsonPrimitive?.content) {
                    "message_start" -> {
                        val message = event["message"]?.jsonObject ?: return@runCatching
                        message["id"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.content) }
                        message["type"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it.content) }
                        message["role"]?.jsonPrimitive?.let { span.setAttribute("gen_ai.response.role", it.content) }
                        message["model"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it.content) }
                        message["usage"]?.jsonObject?.let { usage ->
                            usage["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
                                span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
                            }
                        }
                    }
                    "message_delta" -> {
                        event["delta"]?.jsonObject?.let { delta ->
                            delta["stop_reason"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content?.let {
                                span.setAttribute(GEN_AI_RESPONSE_FINISH_REASONS, listOf(it))
                            }
                        }
                        event["usage"]?.jsonObject?.let { usage ->
                            usage["output_tokens"]?.jsonPrimitive?.intOrNull?.let {
                                span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun detectApiType(url: TracyHttpUrl): String? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val apiSegments = if (segments.firstOrNull() == "v1") segments.drop(1) else segments
        return when {
            apiSegments.isEmpty() -> null
            apiSegments[0] == "messages" -> when {
                apiSegments.size >= 2 && apiSegments[1] == "count_tokens" -> "count_tokens"
                apiSegments.size >= 2 && apiSegments[1] == "batches" -> "batches"
                else -> "messages"
            }
            apiSegments[0] == "files" -> "files"
            apiSegments[0] == "models" -> "models"
            else -> null
        }
    }

    private fun detectOperationName(url: TracyHttpUrl, method: String): String? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val apiSegments = if (segments.firstOrNull() == "v1") segments.drop(1) else segments
        return when {
            apiSegments.isEmpty() -> null
            apiSegments[0] == "messages" -> when {
                apiSegments.size >= 2 && apiSegments[1] == "count_tokens" -> "count_tokens"
                apiSegments.size >= 2 && apiSegments[1] == "batches" -> when {
                    apiSegments.size == 2 -> if (method == "POST") "batches.create" else "batches.list"
                    apiSegments.size >= 4 && apiSegments[3] == "cancel" -> "batches.cancel"
                    apiSegments.size >= 4 && apiSegments[3] == "results" -> "batches.results"
                    else -> if (method == "DELETE") "batches.delete" else "batches.retrieve"
                }
                else -> "chat"
            }
            apiSegments[0] == "files" -> when {
                apiSegments.size == 1 -> if (method == "POST") "files.create" else "files.list"
                apiSegments.size >= 3 && apiSegments[2] == "content" -> "files.content"
                else -> if (method == "DELETE") "files.delete" else "files.retrieve"
            }
            apiSegments[0] == "models" -> if (apiSegments.size == 1) "models.list" else "models.retrieve"
            else -> null
        }
    }

    /**
     * Parses content of the `messages` field when its type is
     * either `ImageBlockParam` or `DocumentBlockParam`.
     *
     * The supported `source` fields are:
     *   1. Images (`ImageBlockParam`): `Base64ImageSource`, `URLImageSource`
     *   2. Documents (`DocumentBlockParam`): `Base64PDFSource`, `URLPDFSource`, `ContentBlockSource` with `ImageBlockParam`
     *
     * See [Messages API Docs](https://platform.claude.com/docs/en/api/messages/create)
     */
    private fun parseMediaContent(body: JsonObject): MediaContent? {
        if (body["messages"] !is JsonArray) {
            return null
        }

        val messages = body["messages"]?.jsonArray ?: return null

        val parts: List<MediaContentPart> = buildList {
            val supportedMessageTypes = listOf("image", "document")

            for (message in messages) {
                // message: { content: [] }
                if (message !is JsonObject || message["content"] !is JsonArray) {
                    continue
                }
                val content = message["content"]?.jsonArray ?: continue

                for (part in content) {
                    val messageType = part.jsonObject["type"]?.jsonPrimitive?.content ?: continue
                    if (messageType !in supportedMessageTypes) {
                        continue
                    }

                    // source is either of:
                    //  1. source: { data, media_type, type: "base64" }
                    //  2. source: { url, type: "url" }
                    //  3. source: { content: [{ type: "image", source: {...} }, ...] }
                    // see: https://platform.claude.com/docs/en/api/messages/create
                    val source = part.jsonObject["source"]?.jsonObject ?: continue
                    val contentParts = parseSource(messageType, source).map {
                        MediaContentPart(it)
                    }
                    addAll(contentParts)
                }
            }
        }

        return MediaContent(parts)
    }

    /**
     * Parses the `source` field of message types:
     *   1. `ImageBlockParam`: both `Base64ImageSource` and `URLImageSource`.
     *   2. `DocumentBlockParam`: `Base64PDFSource`, `URLPDFSource`, and `ContentBlockSource`.
     *
     * See [Messages API Docs](https://platform.claude.com/docs/en/api/messages/create)
     */
    private fun parseSource(messageType: String, source: JsonObject): List<Resource> {
        val sourceType = source["type"]?.jsonPrimitive?.content ?: return emptyList()
        val resources = when (sourceType) {
            "url" -> {
                val url = parseUrl(messageType, source) ?: return emptyList()
                listOf(url)
            }

            "base64" -> {
                val base64 = parseBase64(messageType, source) ?: return emptyList()
                listOf(base64)
            }

            "content" -> parseContent(messageType, source)
            else -> emptyList()
        }
        return resources
    }

    private fun parseUrl(messageType: String, source: JsonObject): Resource.Url? {
        val url = source["url"]?.jsonPrimitive?.content
        if (url == null) {
            logger.warn { "Message with type '$messageType' has no URL source" }
            return null
        }
        // add URL resource
        return Resource.Url(url)
    }

    private fun parseBase64(messageType: String, source: JsonObject): Resource.Base64? {
        val data = source["data"]?.jsonPrimitive?.content
        val mediaType = source["media_type"]?.jsonPrimitive?.content

        if (data == null || mediaType == null) {
            logger.warn { "Message with type '$messageType' misses either 'data' or 'media_type' attribute" }
            return null
        }

        // add base64 resource
        return Resource.Base64(data, mediaType)
    }

    private fun parseContent(messageType: String, source: JsonObject): List<Resource> {
        val content = source["content"]

        if (content == null || content !is JsonArray) {
            logger.warn { "Message with type '$messageType' has no content source" }
            return emptyList()
        }

        // content is an array of `ContentBlockSourceContent`.
        // See: https://platform.claude.com/docs/en/api/messages#content_block_source_content
        val resources: List<Resource> = buildList {
            for (param in content.jsonArray) {
                val type = param.jsonObject["type"]?.jsonPrimitive?.content ?: continue
                // ImageBlockParam
                if (type == "image") {
                    // the image is either Base64ImageSource or URLImageSource
                    val imageSource = param.jsonObject["source"]?.jsonObject ?: continue
                    val resource = parseSource(messageType, imageSource)
                    addAll(resource)
                }
            }
        }

        return resources
    }

    private val extractor: MediaContentExtractor = MediaContentExtractorImpl()

    // https://docs.claude.com/en/api/messages
    private val mappedMessagesRequestAttributes: List<String> = listOf(
        "temperature",
        "model",
        "max_tokens",
        "stop_sequences",
        "thinking",
        "metadata",
        "service_tier",
        "system",
        "top_k",
        "top_p",
        "messages",
        "tools",
        "stream"
    )

    private val mappedMessagesResponseAttributes: List<String> = listOf(
        "id",
        "type",
        "role",
        "model",
        "content",
        "stop_reason",
        "usage"
    )

    private val logger = KotlinLogging.logger {}

    companion object {
        private val STOP_SEQUENCES_KEY = AttributeKey.stringArrayKey("gen_ai.request.stop_sequences")
    }
}
