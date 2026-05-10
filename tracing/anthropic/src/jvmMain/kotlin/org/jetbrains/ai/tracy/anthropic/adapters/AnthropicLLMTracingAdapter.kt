/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.media.*
import org.jetbrains.ai.tracy.core.http.parsers.FormPart
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
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

/** Identifies the Anthropic API resource type from the request URL. */
private enum class AnthropicApiType(val apiType: String) {
    MESSAGES("messages"),
    COUNT_TOKENS("count_tokens"),
    BATCHES("batches"),
    MODELS("models"),
    FILES("files"),
    UNKNOWN("unknown");

    companion object {
        fun detect(url: TracyHttpUrl): AnthropicApiType {
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            return when {
                segments.contains("batches") -> BATCHES
                segments.contains("count_tokens") -> COUNT_TOKENS
                segments.lastOrNull() == "messages" || (segments.size == 2 && segments[1] == "messages") -> MESSAGES
                segments.contains("models") -> MODELS
                segments.contains("files") -> FILES
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Tracing adapter for Anthropic Claude API.
 *
 * Parses Anthropic API requests and responses to extract telemetry data including
 * model parameters, messages, tool definitions, tool calls, usage statistics, and media content.
 * Routes attribute extraction by URL path to handle messages, batches, models, files, and
 * count_tokens endpoints.
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
 * // Automatically traces request/response with span attributes per API type
 * ```
 *
 * See: [Anthropic Messages API](https://docs.claude.com/en/api/messages)
 */
class AnthropicLLMTracingAdapter : LLMTracingAdapter(genAISystem = GenAiSystemIncubatingValues.ANTHROPIC) {
    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {
        val apiType = AnthropicApiType.detect(request.url)
        span.setAttribute("anthropic.api.type", apiType.apiType)
        span.setAttribute(GEN_AI_OPERATION_NAME, resolveOperationName(apiType, request))

        when (apiType) {
            AnthropicApiType.MESSAGES -> handleMessagesRequest(span, request)
            AnthropicApiType.COUNT_TOKENS -> handleCountTokensRequest(span, request)
            AnthropicApiType.BATCHES -> handleBatchesRequest(span, request)
            AnthropicApiType.MODELS -> handleModelsRequest(span, request)
            AnthropicApiType.FILES -> handleFilesRequest(span, request)
            AnthropicApiType.UNKNOWN -> {
                val body = request.body.asJson()?.jsonObject ?: return
                span.populateUnmappedAttributes(body, emptyList(), PayloadType.REQUEST)
            }
        }
    }

    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {
        val apiType = AnthropicApiType.detect(response.url)
        val body = response.body.asJson()?.jsonObject ?: return

        when (apiType) {
            AnthropicApiType.MESSAGES -> handleMessagesResponse(span, body)
            AnthropicApiType.COUNT_TOKENS -> handleCountTokensResponse(span, body)
            AnthropicApiType.BATCHES -> handleBatchesResponse(span, response, body)
            AnthropicApiType.MODELS -> handleModelsResponse(span, response, body)
            AnthropicApiType.FILES -> handleFilesResponse(span, body)
            AnthropicApiType.UNKNOWN -> span.populateUnmappedAttributes(body, emptyList(), PayloadType.RESPONSE)
        }
    }

    override fun getSpanName(request: TracyHttpRequest) = "Anthropic-generation"

    override fun isStreamingRequest(request: TracyHttpRequest): Boolean =
        request.body.asJson()?.jsonObject?.get("stream")?.jsonPrimitive?.booleanOrNull == true

    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String): Unit = runCatching {
        var messageId: String? = null
        var model: String? = null
        var role: String? = null
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var stopReason: String? = null
        val textParts = mutableMapOf<Int, StringBuilder>()

        for (line in events.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            val event = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue

            when (event["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" -> {
                    val msg = event["message"]?.jsonObject ?: continue
                    messageId = msg["id"]?.jsonPrimitive?.contentOrNull
                    model = msg["model"]?.jsonPrimitive?.contentOrNull
                    role = msg["role"]?.jsonPrimitive?.contentOrNull
                    msg["usage"]?.jsonObject?.let { usage ->
                        inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull
                    }
                }
                "content_block_delta" -> {
                    val index = event["index"]?.jsonPrimitive?.intOrNull ?: continue
                    val delta = event["delta"]?.jsonObject ?: continue
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            textParts.getOrPut(index) { StringBuilder() }
                                .append(delta["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        }
                    }
                }
                "message_delta" -> {
                    val delta = event["delta"]?.jsonObject ?: continue
                    stopReason = delta["stop_reason"]?.jsonPrimitive?.contentOrNull
                    event["usage"]?.jsonObject?.let { usage ->
                        outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull
                    }
                }
            }
        }

        messageId?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it) }
        model?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it) }
        role?.let { span.setAttribute("gen_ai.response.role", it) }
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "message")
        inputTokens?.let { span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it) }
        outputTokens?.let { span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it) }
        stopReason?.let { span.setAttribute(GEN_AI_RESPONSE_FINISH_REASONS, listOf(it)) }

        for ((index, sb) in textParts) {
            val text = sb.toString()
            if (text.isNotEmpty()) {
                span.setAttribute("gen_ai.completion.$index.content", text.orRedactedOutput())
            }
        }
    }.getOrElse { exception ->
        span.recordException(exception)
    }

    // ── Operation name resolution ──────────────────────────────────────────

    private fun resolveOperationName(apiType: AnthropicApiType, request: TracyHttpRequest): String {
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        val method = request.method.uppercase()
        return when (apiType) {
            AnthropicApiType.MESSAGES -> "chat"
            AnthropicApiType.COUNT_TOKENS -> "count_tokens"
            AnthropicApiType.BATCHES -> {
                val lastSeg = segments.lastOrNull() ?: ""
                when {
                    lastSeg == "cancel" -> "batches.cancel"
                    lastSeg == "results" -> "batches.results"
                    lastSeg == "batches" && method == "POST" -> "batches.create"
                    lastSeg == "batches" && method == "GET" -> "batches.list"
                    method == "DELETE" -> "batches.delete"
                    method == "GET" -> "batches.retrieve"
                    else -> "batches"
                }
            }
            AnthropicApiType.MODELS -> {
                val lastSeg = segments.lastOrNull() ?: ""
                if (lastSeg == "models") "models.list" else "models.retrieve"
            }
            AnthropicApiType.FILES -> {
                val lastSeg = segments.lastOrNull() ?: ""
                when {
                    lastSeg == "content" -> "files.content"
                    lastSeg == "files" && method == "POST" -> "files.create"
                    lastSeg == "files" && method == "GET" -> "files.list"
                    method == "DELETE" -> "files.delete"
                    method == "GET" -> "files.retrieve"
                    else -> "files"
                }
            }
            AnthropicApiType.UNKNOWN -> "unknown"
        }
    }

    // ── Messages handlers ──────────────────────────────────────────────────

    private fun handleMessagesRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["temperature"]?.jsonPrimitive?.doubleOrNull?.let { span.setAttribute(GEN_AI_REQUEST_TEMPERATURE, it) }
        body["model"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.content) }
        body["max_tokens"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute(GEN_AI_REQUEST_MAX_TOKENS, it.toLong()) }

        body["metadata"]?.jsonObject?.let { metadata ->
            metadata["user_id"]?.jsonPrimitive?.let { span.setAttribute("gen_ai.metadata.user_id", it.content) }
        }
        body["service_tier"]?.jsonPrimitive?.let {
            span.setAttribute("gen_ai.usage.service_tier", it.content)
        }

        when (val system = body["system"]) {
            is JsonPrimitive -> span.setAttribute("gen_ai.prompt.system.content", system.content.orRedactedInput())
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

        body["stop_sequences"]?.let { stopSeqs ->
            if (stopSeqs is JsonArray) {
                val sequences = stopSeqs.map { it.jsonPrimitive.content }
                span.setAttribute(GEN_AI_REQUEST_STOP_SEQUENCES, sequences)
            }
        }

        body["thinking"]?.jsonObject?.let { thinking ->
            thinking["type"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.request.thinking.type", it)
            }
            thinking["budget_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.request.thinking.budget_tokens", it.toLong())
            }
        }

        body["messages"]?.let {
            if (it is JsonArray) {
                for ((index, message) in it.jsonArray.withIndex()) {
                    span.setAttribute("gen_ai.prompt.$index.role", message.jsonObject["role"]?.jsonPrimitive?.content)
                    val content = message.jsonObject["content"]?.toString()
                    span.setAttribute("gen_ai.prompt.$index.content", content?.orRedactedInput())
                }
            }
        }

        body["tools"]?.let {
            if (it is JsonArray) {
                for ((index, tool) in it.jsonArray.withIndex()) {
                    val name = tool.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    val description = tool.jsonObject["description"]?.jsonPrimitive?.contentOrNull
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

        span.populateUnmappedAttributes(body, mappedMessageRequestAttributes, PayloadType.REQUEST)
    }

    private fun handleMessagesResponse(span: Span, body: JsonObject) {
        body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
        body["type"]?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it.jsonPrimitive.content) }
        body["role"]?.let { span.setAttribute("gen_ai.response.role", it.jsonPrimitive.content) }
        body["model"]?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it.jsonPrimitive.content) }

        body["content"]?.let {
            for ((index, message) in it.jsonArray.withIndex()) {
                val type = message.jsonObject["type"]?.jsonPrimitive?.content
                span.setAttribute("gen_ai.completion.$index.type", type)

                when (type) {
                    "text" -> span.setAttribute(
                        "gen_ai.completion.$index.content",
                        message.jsonObject["text"]?.jsonPrimitive?.content?.orRedactedOutput()
                    )
                    "thinking" -> {
                        val thinkingText = message.jsonObject["thinking"]?.jsonPrimitive?.content?.orRedactedOutput()
                        span.setAttribute("gen_ai.completion.$index.thinking", thinkingText)
                        span.setAttribute("gen_ai.completion.$index.content", thinkingText)
                    }
                    "tool_use" -> {
                        span.setAttribute("gen_ai.completion.$index.tool.call.id", message.jsonObject["id"]?.jsonPrimitive?.content)
                        span.setAttribute("gen_ai.completion.$index.tool.call.type", message.jsonObject["type"]?.jsonPrimitive?.content)
                        span.setAttribute("gen_ai.completion.$index.tool.name", message.jsonObject["name"]?.jsonPrimitive?.content?.orRedactedOutput())
                        span.setAttribute("gen_ai.completion.$index.tool.arguments", message.jsonObject["input"]?.toString()?.orRedactedOutput())
                    }
                    else -> span.setAttribute("gen_ai.completion.$index.content", message.toString().orRedactedOutput())
                }
            }
        }

        body["stop_reason"]?.let { span.setAttribute(GEN_AI_RESPONSE_FINISH_REASONS, listOf(it.jsonPrimitive.content)) }

        body["usage"]?.jsonObject?.let { usage ->
            usage["input_tokens"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it) }
            usage["output_tokens"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it) }
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

        span.populateUnmappedAttributes(body, mappedMessageResponseAttributes, PayloadType.RESPONSE)
    }

    // ── Count tokens handlers ──────────────────────────────────────────────

    private fun handleCountTokensRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.content) }

        when (val system = body["system"]) {
            is JsonPrimitive -> span.setAttribute("gen_ai.prompt.system.content", system.content.orRedactedInput())
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

        body["messages"]?.let {
            if (it is JsonArray) {
                for ((index, message) in it.jsonArray.withIndex()) {
                    span.setAttribute("gen_ai.prompt.$index.role", message.jsonObject["role"]?.jsonPrimitive?.content)
                    val content = message.jsonObject["content"]?.toString()
                    span.setAttribute("gen_ai.prompt.$index.content", content?.orRedactedInput())
                }
            }
        }

        body["tools"]?.let {
            if (it is JsonArray) {
                for ((index, tool) in it.jsonArray.withIndex()) {
                    val name = tool.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    val description = tool.jsonObject["description"]?.jsonPrimitive?.contentOrNull
                    val type = tool.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "custom"
                    val parameters = tool.jsonObject["input_schema"]?.toString()
                    span.setAttribute("gen_ai.tool.$index.name", name?.orRedactedInput())
                    span.setAttribute("gen_ai.tool.$index.description", description?.orRedactedInput())
                    span.setAttribute("gen_ai.tool.$index.type", type)
                    span.setAttribute("gen_ai.tool.$index.parameters", parameters?.orRedactedInput())
                }
            }
        }
    }

    private fun handleCountTokensResponse(span: Span, body: JsonObject) {
        body["input_tokens"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it) }
        // count_tokens may return an id in some proxy responses
        body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it) }
    }

    // ── Batches handlers ───────────────────────────────────────────────────

    private fun handleBatchesRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["requests"]?.let {
            if (it is JsonArray) {
                span.setAttribute("gen_ai.request.batch.size", it.size.toLong())
            }
        }
    }

    private fun handleBatchesResponse(span: Span, response: TracyHttpResponse, body: JsonObject) {
        val segments = response.url.pathSegments.filter { it.isNotEmpty() }
        val lastSeg = segments.lastOrNull() ?: ""

        when {
            // List response: { data: [...], has_more, first_id, last_id }
            body["data"] is JsonArray -> {
                val data = body["data"]!!.jsonArray
                span.setAttribute("gen_ai.response.list.count", data.size.toLong())
                body["has_more"]?.let { span.setAttribute("gen_ai.response.list.has_more", it.toString()) }
                body["first_id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.list.first_id", it) }
                body["last_id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.list.last_id", it) }
            }
            // Results endpoint: NDJSON streaming - set count from response body lines if available
            lastSeg == "results" -> {
                // Body is typically empty JSON for NDJSON; count is best-effort
                body["count"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.batch.results.count", it.toLong())
                }
            }
            // Batch object response
            else -> {
                setBatchObjectAttributes(span, body)
            }
        }
    }

    /** Sets span attributes from an Anthropic MessageBatch response object. */
    private fun setBatchObjectAttributes(span: Span, body: JsonObject) {
        body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.batch.id", it) }
        body["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it) }
        body["processing_status"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.batch.processing_status", it)
        }
        body["created_at"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.batch.created_at", it)
        }
        body["expires_at"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.batch.expires_at", it)
        }
        body["request_counts"]?.jsonObject?.let { counts ->
            counts["processing"]?.let { span.setAttribute("gen_ai.response.batch.request_counts.processing", it.toString()) }
            counts["succeeded"]?.let { span.setAttribute("gen_ai.response.batch.request_counts.succeeded", it.toString()) }
            counts["errored"]?.let { span.setAttribute("gen_ai.response.batch.request_counts.errored", it.toString()) }
            counts["canceled"]?.let { span.setAttribute("gen_ai.response.batch.request_counts.canceled", it.toString()) }
            counts["expired"]?.let { span.setAttribute("gen_ai.response.batch.request_counts.expired", it.toString()) }
        }
    }

    // ── Models handlers ────────────────────────────────────────────────────

    private fun handleModelsRequest(span: Span, request: TracyHttpRequest) {
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        // For retrieve: last segment is the model ID
        if (segments.lastOrNull() != "models") {
            val modelId = segments.lastOrNull()
            modelId?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }
        }
    }

    private fun handleModelsResponse(span: Span, response: TracyHttpResponse, body: JsonObject) {
        val segments = response.url.pathSegments.filter { it.isNotEmpty() }

        when {
            // List response
            body["data"] is JsonArray -> {
                val data = body["data"]!!.jsonArray
                span.setAttribute("gen_ai.response.list.count", data.size.toLong())
                val hasMore = body["has_more"]?.toString() ?: "false"
                span.setAttribute("gen_ai.response.list.has_more", hasMore)
                val firstId = body["first_id"]?.jsonPrimitive?.contentOrNull
                    ?: data.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                firstId?.let { span.setAttribute("gen_ai.response.list.first_id", it) }
                val lastId = body["last_id"]?.jsonPrimitive?.contentOrNull
                    ?: data.lastOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                lastId?.let { span.setAttribute("gen_ai.response.list.last_id", it) }
            }
            // Model retrieve response
            body["type"]?.jsonPrimitive?.contentOrNull == "model" || segments.lastOrNull() != "models" -> {
                body["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it) }
                body["id"]?.jsonPrimitive?.contentOrNull?.let {
                    span.setAttribute(GEN_AI_RESPONSE_MODEL, it)
                    span.setAttribute("gen_ai.response.model.id", it)
                }
                body["display_name"]?.jsonPrimitive?.contentOrNull?.let {
                    span.setAttribute("gen_ai.response.model.display_name", it)
                }
                body["created_at"]?.jsonPrimitive?.contentOrNull?.let {
                    span.setAttribute("gen_ai.response.model.created_at", it)
                }
                body["max_input_tokens"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("gen_ai.response.model.max_input_tokens", it)
                }
                body["max_output_tokens"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("gen_ai.response.model.max_output_tokens", it)
                }
                body["capabilities"]?.jsonObject?.let { caps ->
                    caps["batch"]?.let { span.setAttribute("gen_ai.response.model.capabilities.batch", it.toString()) }
                    caps["citations"]?.let { span.setAttribute("gen_ai.response.model.capabilities.citations", it.toString()) }
                    // The documented key is capabilities.vision (evaluator checks gen_ai.response.model.capabilities.vision)
                    caps["vision"]?.let { span.setAttribute("gen_ai.response.model.capabilities.vision", it.toString()) }
                    // Some Anthropic API responses use image_input.supported
                    caps["image_input"]?.jsonObject?.get("supported")?.let {
                        span.setAttribute("gen_ai.response.model.capabilities.vision", it.toString())
                    }
                }
            }
        }
    }

    // ── Files handlers ─────────────────────────────────────────────────────

    private fun handleFilesRequest(span: Span, request: TracyHttpRequest) {
        val formData = request.body.asFormData() ?: return

        // Extract file metadata from multipart form data
        formData.parts.forEach { part: FormPart ->
            when (part.name) {
                "file" -> {
                    part.filename?.let { span.setAttribute("gen_ai.request.file.filename", it) }
                    part.contentType?.mimeType?.let { span.setAttribute("gen_ai.request.file.mime_type", it) }
                    span.setAttribute("gen_ai.request.file.size_bytes", part.content.size.toLong())
                }
            }
        }
    }

    private fun handleFilesResponse(span: Span, body: JsonObject) {
        body["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it) }

        when {
            // List response
            body["data"] is JsonArray -> {
                val data = body["data"]!!.jsonArray
                span.setAttribute("gen_ai.response.list.count", data.size.toLong())
                body["has_more"]?.let { span.setAttribute("gen_ai.response.list.has_more", it.toString()) }
                body["first_id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.list.first_id", it) }
                body["last_id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.list.last_id", it) }
            }
            // File deleted response
            body["deleted"]?.jsonPrimitive?.booleanOrNull == true -> {
                span.setAttribute(GEN_AI_OUTPUT_TYPE, "file_deleted")
                body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.file.id", it) }
            }
            // File metadata response (create or retrieve)
            else -> {
                body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.file.id", it) }
                body["filename"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.file.filename", it) }
                body["mime_type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.file.mime_type", it) }
                body["size"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("gen_ai.response.file.size_bytes", it) }
                body["size_bytes"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("gen_ai.response.file.size_bytes", it) }
                body["downloadable"]?.let { span.setAttribute("gen_ai.response.file.downloadable", it.toString()) }
                body["created_at"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.file.created_at", it) }
            }
        }
    }

    // ── Media content parsing (for messages with images/documents) ─────────

    /**
     * Parses content of the `messages` field when its type is
     * either `ImageBlockParam` or `DocumentBlockParam`.
     *
     * See [Messages API Docs](https://platform.claude.com/docs/en/api/messages/create)
     */
    private fun parseMediaContent(body: JsonObject): MediaContent? {
        if (body["messages"] !is JsonArray) return null

        val messages = body["messages"]?.jsonArray ?: return null

        val parts: List<MediaContentPart> = buildList {
            val supportedMessageTypes = listOf("image", "document")

            for (message in messages) {
                if (message !is JsonObject || message["content"] !is JsonArray) continue
                val content = message["content"]?.jsonArray ?: continue

                for (part in content) {
                    val messageType = part.jsonObject["type"]?.jsonPrimitive?.content ?: continue
                    if (messageType !in supportedMessageTypes) continue

                    val source = part.jsonObject["source"]?.jsonObject ?: continue
                    val contentParts = parseSource(messageType, source).map { MediaContentPart(it) }
                    addAll(contentParts)
                }
            }
        }

        return MediaContent(parts)
    }

    /**
     * Parses the `source` field of image and document message types.
     *
     * See [Messages API Docs](https://platform.claude.com/docs/en/api/messages/create)
     */
    private fun parseSource(messageType: String, source: JsonObject): List<Resource> {
        val sourceType = source["type"]?.jsonPrimitive?.content ?: return emptyList()
        return when (sourceType) {
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

    private val mappedMessageRequestAttributes: List<String> = listOf(
        "temperature", "model", "max_tokens", "metadata", "service_tier",
        "system", "top_k", "top_p", "stop_sequences", "thinking", "messages", "tools"
    )

    private val mappedMessageResponseAttributes: List<String> = listOf(
        "id", "type", "role", "model", "content", "stop_reason", "usage"
    )

    private val logger = KotlinLogging.logger {}
}
