/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.media.*
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.ContentKind
import org.jetbrains.ai.tracy.core.policy.contentTracingAllowed
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*
import kotlinx.serialization.json.*
import mu.KotlinLogging

/** Detected Anthropic API type and operation for a given HTTP call. */
private data class AnthropicEndpointInfo(
    /** Value to emit as `anthropic.api.type`. */
    val apiType: String,
    /** Value to emit as `gen_ai.operation.name`. */
    val operation: String,
    /** Value to emit as `gen_ai.output.type`, or null to omit the attribute. */
    val outputType: String?,
)

/**
 * Tracing adapter for Anthropic Claude API.
 *
 * Parses Anthropic API requests and responses to extract telemetry data for:
 * - **Messages** (`/v1/messages`): model parameters, messages, tool definitions, tool calls, usage, and media content.
 * - **Count Tokens** (`/v1/messages/count_tokens`): token count from the response.
 * - **Batches** (`/v1/messages/batches`): batch metadata and request counts.
 * - **Files** (`/v1/files`): file metadata (id, filename, mime type, size, downloadable).
 * - **Models** (`/v1/models`): model list metadata and per-model capability attributes.
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
 * ```
 *
 * See: [Anthropic API](https://docs.anthropic.com/en/api/)
 */
class AnthropicLLMTracingAdapter : LLMTracingAdapter(genAISystem = GenAiSystemIncubatingValues.ANTHROPIC) {
    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {
        val info = detectEndpoint(request.url, request.method)
        span.setAttribute("anthropic.api.type", info.apiType)
        span.setAttribute(GEN_AI_OPERATION_NAME, info.operation)

        when (info.apiType) {
            "messages", "count_tokens" -> handleMessagesRequestAttributes(span, request)
            "batches" -> handleBatchesRequestAttributes(span, request)
            "files" -> handleFilesRequestAttributes(span, request)
            "models" -> handleModelsRequestAttributes(span, request)
        }
    }

    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {
        val info = detectEndpoint(response.url, response.requestMethod)
        info.outputType?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it) }

        val body = response.body.asJson()?.jsonObject ?: return

        when (info.apiType) {
            "messages" -> handleMessagesResponseAttributes(span, body)
            "count_tokens" -> handleCountTokensResponseAttributes(span, body)
            "batches" -> handleBatchesResponseAttributes(span, body)
            "files" -> handleFilesResponseAttributes(span, body, info.operation)
            "models" -> handleModelsResponseAttributes(span, body, info.operation)
        }
    }

    override fun getSpanName(request: TracyHttpRequest) = "Anthropic-generation"

    // streaming is not supported
    override fun isStreamingRequest(request: TracyHttpRequest) = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) = Unit

    // ── Endpoint detection ────────────────────────────────────────────────────

    private fun detectEndpoint(url: TracyHttpUrl, method: String): AnthropicEndpointInfo {
        val segments = url.pathSegments.filter { it.isNotEmpty() }

        return when {
            segments.contains("count_tokens") -> AnthropicEndpointInfo(
                apiType = "count_tokens",
                operation = "count_tokens",
                outputType = null,
            )

            segments.contains("batches") -> {
                val batchIdx = segments.indexOf("batches")
                val hasBatchId = segments.size > batchIdx + 1
                val operation = when {
                    hasBatchId && segments.last() == "results" -> "batches.results"
                    hasBatchId && method == "DELETE" -> "batches.delete"
                    hasBatchId && method == "POST" -> "batches.cancel"
                    hasBatchId -> "batches.retrieve"
                    method == "GET" -> "batches.list"
                    else -> "batches.create"
                }
                AnthropicEndpointInfo(apiType = "batches", operation = operation, outputType = "message_batch")
            }

            segments.contains("files") -> {
                val fileIdx = segments.indexOf("files")
                val hasFileId = segments.size > fileIdx + 1
                val operation = when {
                    hasFileId && segments.last() == "content" -> "files.content"
                    hasFileId && method == "DELETE" -> "files.delete"
                    hasFileId -> "files.retrieve"
                    method == "GET" -> "files.list"
                    else -> "files.create"
                }
                val outputType = when (operation) {
                    "files.delete" -> "file_deleted"
                    "files.list" -> null
                    else -> "file"
                }
                AnthropicEndpointInfo(apiType = "files", operation = operation, outputType = outputType)
            }

            segments.contains("models") -> {
                val modelIdx = segments.indexOf("models")
                val hasModelId = segments.size > modelIdx + 1
                val operation = if (hasModelId) "models.retrieve" else "models.list"
                val outputType = if (hasModelId) "model" else null
                AnthropicEndpointInfo(apiType = "models", operation = operation, outputType = outputType)
            }

            else -> AnthropicEndpointInfo(apiType = "messages", operation = "chat", outputType = "message")
        }
    }

    // ── Request attribute handlers ────────────────────────────────────────────

    private fun handleMessagesRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["temperature"]?.jsonPrimitive?.doubleOrNull?.let { span.setAttribute(GEN_AI_REQUEST_TEMPERATURE, it) }
        body["model"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.content) }
        body["max_tokens"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute(GEN_AI_REQUEST_MAX_TOKENS, it.toLong()) }

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
                    val type = tool.jsonObject["type"]?.jsonPrimitive?.contentOrNull
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

    private fun handleBatchesRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        body["requests"]?.let { requests ->
            if (requests is JsonArray) {
                span.setAttribute("gen_ai.request.batch.size", requests.size.toLong())
            }
        }
    }

    private fun handleFilesRequestAttributes(span: Span, request: TracyHttpRequest) {
        val formData = request.body.asFormData() ?: return
        val filePart = formData.parts.firstOrNull { it.name == "file" } ?: return
        filePart.filename?.let { span.setAttribute("gen_ai.request.file.filename", it) }
        filePart.contentType?.mimeType?.let { span.setAttribute("gen_ai.request.file.mime_type", it) }
        span.setAttribute("gen_ai.request.file.size_bytes", filePart.content.size.toLong())
    }

    private fun handleModelsRequestAttributes(span: Span, request: TracyHttpRequest) {
        // model ID may be in the URL path for retrieve; extract if present
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        val modelIdx = segments.indexOf("models")
        if (modelIdx >= 0 && segments.size > modelIdx + 1) {
            val modelId = segments[modelIdx + 1]
            span.setAttribute(GEN_AI_REQUEST_MODEL, modelId)
        }
    }

    // ── Response attribute handlers ───────────────────────────────────────────

    private fun handleMessagesResponseAttributes(span: Span, body: JsonObject) {
        body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
        body["type"]?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it.jsonPrimitive.content) }
        body["role"]?.let { span.setAttribute("gen_ai.response.role", it.jsonPrimitive.content) }
        body["model"]?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it.jsonPrimitive.content) }

        // collecting response messages
        body["content"]?.let {
            for ((index, message) in it.jsonArray.withIndex()) {
                val type = message.jsonObject["type"]?.jsonPrimitive?.content
                span.setAttribute("gen_ai.completion.$index.type", type)

                when (type) {
                    "text" -> {
                        span.setAttribute(
                            "gen_ai.completion.$index.content",
                            message.jsonObject["text"]?.jsonPrimitive?.content?.orRedactedOutput()
                        )
                    }

                    "tool_use" -> {
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
                        span.setAttribute(
                            "gen_ai.completion.$index.tool.arguments",
                            toolCall.jsonObject["input"]?.toString()?.orRedactedOutput()
                        )
                    }

                    else -> {
                        span.setAttribute("gen_ai.completion.$index.content", message.toString().orRedactedOutput())
                    }
                }
            }
        }

        // finish reason
        body["stop_reason"]?.let {
            span.setAttribute(GEN_AI_RESPONSE_FINISH_REASONS, listOf(it.jsonPrimitive.content))
        }

        // collecting usage stats
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

    private fun handleCountTokensResponseAttributes(span: Span, body: JsonObject) {
        body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
        body["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
        }
    }

    private fun handleBatchesResponseAttributes(span: Span, body: JsonObject) {
        body["id"]?.let { span.setAttribute("gen_ai.response.batch.id", it.jsonPrimitive.content) }
        body["type"]?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it.jsonPrimitive.content) }
        body["processing_status"]?.let {
            span.setAttribute("gen_ai.response.batch.processing_status", it.jsonPrimitive.content)
        }
        body["created_at"]?.let {
            span.setAttribute("gen_ai.response.batch.created_at", it.jsonPrimitive.content)
        }
        body["expires_at"]?.let {
            span.setAttribute("gen_ai.response.batch.expires_at", it.jsonPrimitive.content)
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
        // list endpoint: data array with list metadata
        body["data"]?.let { data ->
            if (data is JsonArray) {
                span.setAttribute("gen_ai.response.list.count", data.size.toLong())
            }
        }
        body["has_more"]?.let {
            span.setAttribute("gen_ai.response.list.has_more", it.jsonPrimitive.booleanOrNull ?: false)
        }
        body["first_id"]?.let {
            span.setAttribute("gen_ai.response.list.first_id", it.jsonPrimitive.contentOrNull ?: "")
        }
        body["last_id"]?.let {
            span.setAttribute("gen_ai.response.list.last_id", it.jsonPrimitive.contentOrNull ?: "")
        }
    }

    private fun handleFilesResponseAttributes(span: Span, body: JsonObject, operation: String) {
        when (operation) {
            "files.delete" -> {
                // delete response: {"id": "...", "deleted": true}
                body["id"]?.let { span.setAttribute("gen_ai.response.file.id", it.jsonPrimitive.content) }
            }
            "files.list" -> {
                body["data"]?.let { data ->
                    if (data is JsonArray) {
                        span.setAttribute("gen_ai.response.list.count", data.size.toLong())
                    }
                }
                body["has_more"]?.let {
                    span.setAttribute("gen_ai.response.list.has_more", it.jsonPrimitive.booleanOrNull ?: false)
                }
                body["first_id"]?.let {
                    span.setAttribute("gen_ai.response.list.first_id", it.jsonPrimitive.contentOrNull ?: "")
                }
                body["last_id"]?.let {
                    span.setAttribute("gen_ai.response.list.last_id", it.jsonPrimitive.contentOrNull ?: "")
                }
            }
            else -> {
                // create or retrieve: file object
                body["id"]?.let { span.setAttribute("gen_ai.response.file.id", it.jsonPrimitive.content) }
                body["filename"]?.let {
                    span.setAttribute("gen_ai.response.file.filename", it.jsonPrimitive.content)
                }
                body["mime_type"]?.let {
                    span.setAttribute("gen_ai.response.file.mime_type", it.jsonPrimitive.content)
                }
                body["size"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.file.size_bytes", it.toLong())
                }
                body["created_at"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.file.created_at", it.toLong())
                }
                body["downloadable"]?.jsonPrimitive?.booleanOrNull?.let {
                    span.setAttribute("gen_ai.response.file.downloadable", it)
                }
            }
        }
    }

    private fun handleModelsResponseAttributes(span: Span, body: JsonObject, operation: String) {
        when (operation) {
            "models.list" -> {
                body["data"]?.let { data ->
                    if (data is JsonArray) {
                        span.setAttribute("gen_ai.response.list.count", data.size.toLong())
                    }
                }
                body["has_more"]?.let {
                    span.setAttribute("gen_ai.response.list.has_more", it.jsonPrimitive.booleanOrNull ?: false)
                }
                body["first_id"]?.let {
                    span.setAttribute("gen_ai.response.list.first_id", it.jsonPrimitive.contentOrNull ?: "")
                }
                body["last_id"]?.let {
                    span.setAttribute("gen_ai.response.list.last_id", it.jsonPrimitive.contentOrNull ?: "")
                }
            }
            "models.retrieve" -> {
                body["id"]?.let {
                    span.setAttribute(GEN_AI_RESPONSE_MODEL, it.jsonPrimitive.content)
                    span.setAttribute("gen_ai.response.model.id", it.jsonPrimitive.content)
                }
                body["display_name"]?.let {
                    span.setAttribute("gen_ai.response.model.display_name", it.jsonPrimitive.content)
                }
                body["created_at"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.model.created_at", it.toLong())
                }
                body["max_input_tokens"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.model.max_input_tokens", it.toLong())
                }
                body["max_output_tokens"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.model.max_output_tokens", it.toLong())
                }
                body["capabilities"]?.jsonObject?.let { caps ->
                    caps["batch"]?.jsonPrimitive?.booleanOrNull?.let {
                        span.setAttribute("gen_ai.response.model.capabilities.batch", it)
                    }
                    caps["citations"]?.jsonPrimitive?.booleanOrNull?.let {
                        span.setAttribute("gen_ai.response.model.capabilities.citations", it)
                    }
                    caps["vision"]?.jsonPrimitive?.booleanOrNull?.let {
                        span.setAttribute("gen_ai.response.model.capabilities.vision", it)
                    }
                }
            }
        }
    }

    // ── Media content helpers (for messages endpoint) ─────────────────────────

    /**
     * Parses content of the `messages` field when its type is
     * either `ImageBlockParam` or `DocumentBlockParam`.
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
                if (message !is JsonObject || message["content"] !is JsonArray) {
                    continue
                }
                val content = message["content"]?.jsonArray ?: continue

                for (part in content) {
                    val messageType = part.jsonObject["type"]?.jsonPrimitive?.content ?: continue
                    if (messageType !in supportedMessageTypes) {
                        continue
                    }

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

    private fun parseSource(messageType: String, source: JsonObject): List<Resource> {
        val sourceType = source["type"]?.jsonPrimitive?.content ?: return emptyList()
        return when (sourceType) {
            "url" -> listOfNotNull(parseUrl(messageType, source))
            "base64" -> listOfNotNull(parseBase64(messageType, source))
            "content" -> parseContent(messageType, source)
            else -> emptyList()
        }
    }

    private fun parseUrl(messageType: String, source: JsonObject): Resource.Url? {
        val url = source["url"]?.jsonPrimitive?.content
        if (url == null) {
            logger.warn { "Message with type '$messageType' has no URL source" }
            return null
        }
        return Resource.Url(url)
    }

    private fun parseBase64(messageType: String, source: JsonObject): Resource.Base64? {
        val data = source["data"]?.jsonPrimitive?.content
        val mediaType = source["media_type"]?.jsonPrimitive?.content

        if (data == null || mediaType == null) {
            logger.warn { "Message with type '$messageType' misses either 'data' or 'media_type' attribute" }
            return null
        }

        return Resource.Base64(data, mediaType)
    }

    private fun parseContent(messageType: String, source: JsonObject): List<Resource> {
        val content = source["content"]

        if (content == null || content !is JsonArray) {
            logger.warn { "Message with type '$messageType' has no content source" }
            return emptyList()
        }

        return buildList {
            for (param in content.jsonArray) {
                val type = param.jsonObject["type"]?.jsonPrimitive?.content ?: continue
                if (type == "image") {
                    val imageSource = param.jsonObject["source"]?.jsonObject ?: continue
                    addAll(parseSource(messageType, imageSource))
                }
            }
        }
    }

    private val extractor: MediaContentExtractor = MediaContentExtractorImpl()

    // https://docs.claude.com/en/api/messages
    private val mappedMessagesRequestAttributes: List<String> = listOf(
        "temperature",
        "model",
        "max_tokens",
        "metadata",
        "service_tier",
        "system",
        "top_k",
        "top_p",
        "messages",
        "tools"
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
}
