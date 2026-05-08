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
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*
import kotlinx.serialization.json.*
import mu.KotlinLogging

/**
 * Tracing adapter for Anthropic Claude API.
 *
 * Routes each API call by URL path to the appropriate handler, which sets
 * endpoint-specific span attributes. Supports messages, batches, files, models,
 * and count_tokens endpoints. Streaming is supported for the messages endpoint.
 *
 * Attribute namespaces:
 * - `gen_ai.*` — OTel GenAI registry attributes
 * - `anthropic.*` — Anthropic-specific attributes
 * - `tracy.*` — unmapped payload fields captured as fallback
 *
 * See: [Anthropic API reference](https://docs.anthropic.com/en/api/overview)
 */
class AnthropicLLMTracingAdapter : LLMTracingAdapter(genAISystem = GenAiSystemIncubatingValues.ANTHROPIC) {

    // ---------------------------------------------------------------------------
    // Endpoint detection
    // ---------------------------------------------------------------------------

    private enum class ApiType { MESSAGES, COUNT_TOKENS, BATCHES, FILES, MODELS }

    private fun detectApiType(url: TracyHttpUrl): ApiType {
        val segments = url.pathSegments
        return when {
            "batches" in segments -> ApiType.BATCHES
            "count_tokens" in segments -> ApiType.COUNT_TOKENS
            "files" in segments -> ApiType.FILES
            "models" in segments -> ApiType.MODELS
            else -> ApiType.MESSAGES
        }
    }

    private fun anthropicApiTypeValue(apiType: ApiType): String = when (apiType) {
        ApiType.MESSAGES -> "messages"
        ApiType.COUNT_TOKENS -> "count_tokens"
        ApiType.BATCHES -> "batches"
        ApiType.FILES -> "files"
        ApiType.MODELS -> "models"
    }

    /** Derives a unique operation name for each route to avoid op-name collisions. */
    private fun operationName(apiType: ApiType, url: TracyHttpUrl, method: String): String =
        when (apiType) {
            ApiType.MESSAGES -> "chat"
            ApiType.COUNT_TOKENS -> "count_tokens"
            ApiType.BATCHES -> {
                val last = url.pathSegments.lastOrNull()
                when {
                    last == "results" -> "batches.results"
                    last == "batches" && method == "POST" -> "batches.create"
                    last == "batches" -> "batches.list"
                    method == "DELETE" -> "batches.delete"
                    method == "POST" -> "batches.cancel"
                    else -> "batches.retrieve"
                }
            }
            ApiType.FILES -> {
                val last = url.pathSegments.lastOrNull()
                when {
                    last == "content" -> "files.content"
                    last == "files" && method == "POST" -> "files.create"
                    last == "files" -> "files.list"
                    method == "DELETE" -> "files.delete"
                    else -> "files.retrieve"
                }
            }
            ApiType.MODELS -> {
                val last = url.pathSegments.lastOrNull()
                if (last == "models") "models.list" else "models.retrieve"
            }
        }

    // ---------------------------------------------------------------------------
    // LLMTracingAdapter overrides
    // ---------------------------------------------------------------------------

    override fun getSpanName(request: TracyHttpRequest) = "Anthropic-generation"

    override fun isStreamingRequest(request: TracyHttpRequest): Boolean {
        val body = request.body.asJson()?.jsonObject ?: return false
        return body["stream"]?.jsonPrimitive?.booleanOrNull == true
    }

    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {
        val apiType = detectApiType(request.url)
        span.setAttribute("anthropic.api.type", anthropicApiTypeValue(apiType))
        span.setAttribute("gen_ai.operation.name", operationName(apiType, request.url, request.method))
        when (apiType) {
            ApiType.MESSAGES -> handleMessagesRequest(span, request)
            ApiType.COUNT_TOKENS -> handleCountTokensRequest(span, request)
            ApiType.BATCHES -> handleBatchesRequest(span, request)
            ApiType.FILES -> handleFilesRequest(span, request)
            ApiType.MODELS -> handleModelsRequest(span, request)
        }
    }

    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {
        val apiType = detectApiType(response.url)
        when (apiType) {
            ApiType.MESSAGES -> handleMessagesResponse(span, response)
            ApiType.COUNT_TOKENS -> handleCountTokensResponse(span, response)
            ApiType.BATCHES -> handleBatchesResponse(span, response)
            ApiType.FILES -> handleFilesResponse(span, response)
            ApiType.MODELS -> handleModelsResponse(span, response)
        }
    }

    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {
        if (detectApiType(url) != ApiType.MESSAGES) return
        parseMessagesStream(span, events)
    }

    // ---------------------------------------------------------------------------
    // Messages endpoint
    // ---------------------------------------------------------------------------

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

        body["stop_sequences"]?.jsonArray?.let { sequences ->
            val values = sequences.mapNotNull { it.jsonPrimitive.contentOrNull }
            if (values.isNotEmpty()) span.setAttribute("gen_ai.request.stop_sequences", values.joinToString(","))
        }

        body["thinking"]?.jsonObject?.let { thinking ->
            thinking["type"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.request.thinking.type", it)
            }
            thinking["budget_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.request.thinking.budget_tokens", it.toLong())
            }
        }

        body["messages"]?.jsonArray?.let { messages ->
            for ((index, message) in messages.withIndex()) {
                span.setAttribute("gen_ai.prompt.$index.role", message.jsonObject["role"]?.jsonPrimitive?.content)
                val content = message.jsonObject["content"]?.toString()
                span.setAttribute("gen_ai.prompt.$index.content", content?.orRedactedInput())
            }
        }

        body["tools"]?.jsonArray?.let { tools ->
            for ((index, tool) in tools.withIndex()) {
                span.setAttribute("gen_ai.tool.$index.name", tool.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.orRedactedInput())
                span.setAttribute("gen_ai.tool.$index.description", tool.jsonObject["description"]?.jsonPrimitive?.contentOrNull?.orRedactedInput())
                // Anthropic custom tools have no explicit type field; default to "custom" per OTel GenAI spec
                span.setAttribute("gen_ai.tool.$index.type", tool.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "custom")
                span.setAttribute("gen_ai.tool.$index.parameters", tool.jsonObject["input_schema"]?.toString()?.orRedactedInput())
            }
        }

        if (contentTracingAllowed(ContentKind.INPUT)) {
            val mediaContent = parseMediaContent(body)
            if (mediaContent != null) extractor.setUploadableContentAttributes(span, field = "input", mediaContent)
        }

        span.populateUnmappedAttributes(body, mappedMessagesRequestAttributes, PayloadType.REQUEST)
    }

    private fun handleMessagesResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.content) }
        body["type"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it.content) }
        body["role"]?.jsonPrimitive?.let { span.setAttribute("gen_ai.response.role", it.content) }
        body["model"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it.content) }

        body["content"]?.jsonArray?.let { content ->
            for ((index, block) in content.withIndex()) {
                val type = block.jsonObject["type"]?.jsonPrimitive?.content
                span.setAttribute("gen_ai.completion.$index.type", type)
                when (type) {
                    "text" -> {
                        span.setAttribute(
                            "gen_ai.completion.$index.content",
                            block.jsonObject["text"]?.jsonPrimitive?.content?.orRedactedOutput()
                        )
                    }
                    "thinking" -> {
                        // Extended thinking block — expose both .content and .thinking
                        val thinkingText = block.jsonObject["thinking"]?.jsonPrimitive?.contentOrNull?.orRedactedOutput()
                        span.setAttribute("gen_ai.completion.$index.content", thinkingText)
                        span.setAttribute("gen_ai.completion.$index.thinking", thinkingText)
                    }
                    "tool_use" -> {
                        span.setAttribute("gen_ai.completion.$index.tool.call.id", block.jsonObject["id"]?.jsonPrimitive?.content)
                        span.setAttribute("gen_ai.completion.$index.tool.call.type", block.jsonObject["type"]?.jsonPrimitive?.content)
                        span.setAttribute("gen_ai.completion.$index.tool.name", block.jsonObject["name"]?.jsonPrimitive?.content?.orRedactedOutput())
                        span.setAttribute("gen_ai.completion.$index.tool.arguments", block.jsonObject["input"]?.toString()?.orRedactedOutput())
                    }
                    else -> {
                        span.setAttribute("gen_ai.completion.$index.content", block.toString().orRedactedOutput())
                    }
                }
            }
        }

        body["stop_reason"]?.jsonPrimitive?.let {
            span.setAttribute(GEN_AI_RESPONSE_FINISH_REASONS, listOf(it.content))
        }

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

        span.populateUnmappedAttributes(body, mappedMessagesResponseAttributes, PayloadType.RESPONSE)
    }

    /** Parses Anthropic SSE event stream for messages.createStreaming. */
    private fun parseMessagesStream(span: Span, events: String) {
        val contentBlocks = mutableMapOf<Int, StringBuilder>()
        val thinkingBlocks = mutableMapOf<Int, StringBuilder>()

        for (line in events.split("\n")) {
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            if (data.isEmpty()) continue

            runCatching {
                val json = Json.parseToJsonElement(data).jsonObject
                when (json["type"]?.jsonPrimitive?.content) {
                    "message_start" -> json["message"]?.jsonObject?.let { msg ->
                        msg["id"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.content) }
                        msg["type"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it.content) }
                        msg["role"]?.jsonPrimitive?.let { span.setAttribute("gen_ai.response.role", it.content) }
                        msg["model"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it.content) }
                        msg["usage"]?.jsonObject?.let { usage ->
                            usage["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
                                span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
                            }
                        }
                    }
                    "content_block_start" -> {
                        val index = json["index"]?.jsonPrimitive?.intOrNull ?: return@runCatching
                        json["content_block"]?.jsonObject?.let { block ->
                            val blockType = block["type"]?.jsonPrimitive?.content ?: return@runCatching
                            span.setAttribute("gen_ai.completion.$index.type", blockType)
                        }
                    }
                    "content_block_delta" -> {
                        val index = json["index"]?.jsonPrimitive?.intOrNull ?: return@runCatching
                        json["delta"]?.jsonObject?.let { delta ->
                            when (delta["type"]?.jsonPrimitive?.content) {
                                "text_delta" -> delta["text"]?.jsonPrimitive?.content?.let {
                                    contentBlocks.getOrPut(index) { StringBuilder() }.append(it)
                                }
                                "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.content?.let {
                                    thinkingBlocks.getOrPut(index) { StringBuilder() }.append(it)
                                }
                            }
                        }
                    }
                    "message_delta" -> {
                        json["delta"]?.jsonObject?.let { delta ->
                            delta["stop_reason"]?.jsonPrimitive?.let {
                                span.setAttribute(GEN_AI_RESPONSE_FINISH_REASONS, listOf(it.content))
                            }
                        }
                        json["usage"]?.jsonObject?.let { usage ->
                            usage["output_tokens"]?.jsonPrimitive?.intOrNull?.let {
                                span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it)
                            }
                        }
                    }
                }
            }
        }

        for ((index, content) in contentBlocks) {
            span.setAttribute("gen_ai.completion.$index.content", content.toString().orRedactedOutput())
        }
        for ((index, thinking) in thinkingBlocks) {
            span.setAttribute("gen_ai.completion.$index.thinking", thinking.toString().orRedactedOutput())
        }
    }

    // ---------------------------------------------------------------------------
    // Count tokens endpoint
    // ---------------------------------------------------------------------------

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

        body["messages"]?.jsonArray?.let { messages ->
            for ((index, message) in messages.withIndex()) {
                span.setAttribute("gen_ai.prompt.$index.role", message.jsonObject["role"]?.jsonPrimitive?.content)
                val content = message.jsonObject["content"]?.toString()
                span.setAttribute("gen_ai.prompt.$index.content", content?.orRedactedInput())
            }
        }

        body["tools"]?.jsonArray?.let { tools ->
            for ((index, tool) in tools.withIndex()) {
                span.setAttribute("gen_ai.tool.$index.name", tool.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.orRedactedInput())
                span.setAttribute("gen_ai.tool.$index.type", tool.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "custom")
                span.setAttribute("gen_ai.tool.$index.parameters", tool.jsonObject["input_schema"]?.toString()?.orRedactedInput())
            }
        }
    }

    private fun handleCountTokensResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["input_tokens"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it) }
    }

    // ---------------------------------------------------------------------------
    // Batches endpoint
    // ---------------------------------------------------------------------------

    private fun handleBatchesRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        body["requests"]?.jsonArray?.let { requests ->
            span.setAttribute("gen_ai.request.batch.size", requests.size.toLong())
        }
    }

    private fun handleBatchesResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // List response
        if (body.containsKey("data")) {
            body["data"]?.jsonArray?.let { data ->
                span.setAttribute("gen_ai.response.list.count", data.size.toLong())
            }
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.list.has_more", it.toString())
            }
            body["first_id"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.response.list.first_id", it)
            }
            body["last_id"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.response.list.last_id", it)
            }
            return
        }

        // Single batch object (create / retrieve / cancel / delete)
        body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.batch.id", it) }
        body["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it) }
        body["processing_status"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.batch.processing_status", it)
        }
        body["created_at"]?.let { span.setAttribute("gen_ai.response.batch.created_at", it.toString()) }
        body["expires_at"]?.let { span.setAttribute("gen_ai.response.batch.expires_at", it.toString()) }
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

    // ---------------------------------------------------------------------------
    // Files endpoint
    // ---------------------------------------------------------------------------

    private fun handleFilesRequest(span: Span, request: TracyHttpRequest) {
        val formData = request.body.asFormData() ?: return
        for (part in formData.parts) {
            if (part.name == "file") {
                span.setAttribute("gen_ai.request.file.size_bytes", part.content.size.toLong())
                part.filename?.let { span.setAttribute("gen_ai.request.file.filename", it) }
                part.contentType?.mimeType?.let { span.setAttribute("gen_ai.request.file.mime_type", it) }
                break
            }
        }
    }

    private fun handleFilesResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // List response
        if (body.containsKey("data")) {
            body["data"]?.jsonArray?.let { data ->
                span.setAttribute("gen_ai.response.list.count", data.size.toLong())
            }
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.list.has_more", it.toString())
            }
            body["first_id"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.response.list.first_id", it)
            }
            body["last_id"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.response.list.last_id", it)
            }
            return
        }

        // Single file object
        body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.file.id", it) }
        body["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it) }
        body["filename"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.file.filename", it) }
        body["mime_type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.file.mime_type", it) }
        body["size"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("gen_ai.response.file.size_bytes", it.toLong()) }
        body["downloadable"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("gen_ai.response.file.downloadable", it.toString())
        }
        body["created_at"]?.let { span.setAttribute("gen_ai.response.file.created_at", it.toString()) }
    }

    // ---------------------------------------------------------------------------
    // Models endpoint
    // ---------------------------------------------------------------------------

    private fun handleModelsRequest(span: Span, request: TracyHttpRequest) {
        // Model ID is in the URL for retrieve (e.g., /v1/models/claude-haiku-4-5)
        val segments = request.url.pathSegments
        val modelsIdx = segments.indexOf("models")
        if (modelsIdx >= 0 && modelsIdx + 1 < segments.size) {
            span.setAttribute(GEN_AI_REQUEST_MODEL, segments[modelsIdx + 1])
        }
    }

    private fun handleModelsResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // List response
        if (body.containsKey("data")) {
            body["data"]?.jsonArray?.let { data ->
                span.setAttribute("gen_ai.response.list.count", data.size.toLong())
            }
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.list.has_more", it.toString())
            }
            body["first_id"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.response.list.first_id", it)
            }
            body["last_id"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.response.list.last_id", it)
            }
            return
        }

        // Single model object
        body["id"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.model.id", it)
            span.setAttribute(GEN_AI_RESPONSE_MODEL, it)
        }
        body["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it) }
        body["display_name"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.model.display_name", it)
        }
        body["created_at"]?.let { span.setAttribute("gen_ai.response.model.created_at", it.toString()) }
        body["max_input_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.response.model.max_input_tokens", it.toLong())
        }
        body["max_output_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.response.model.max_output_tokens", it.toLong())
        }
        body["capabilities"]?.jsonObject?.let { caps ->
            caps["batch"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.model.capabilities.batch", it.toString())
            }
            caps["citations"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.model.capabilities.citations", it.toString())
            }
            // API field is image_input.supported; attribute name is capabilities.vision
            caps["image_input"]?.jsonObject?.get("supported")?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.model.capabilities.vision", it.toString())
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Media content parsing (images and documents in messages requests)
    // ---------------------------------------------------------------------------

    /**
     * Parses multimodal content (`ImageBlockParam`, `DocumentBlockParam`) from the messages body.
     *
     * See [Messages API](https://platform.claude.com/docs/en/api/messages/create)
     */
    private fun parseMediaContent(body: JsonObject): MediaContent? {
        val messages = body["messages"]?.jsonArray ?: return null

        val parts: List<MediaContentPart> = buildList {
            val supportedTypes = listOf("image", "document")
            for (message in messages) {
                if (message !is JsonObject || message["content"] !is JsonArray) continue
                val content = message["content"]?.jsonArray ?: continue
                for (part in content) {
                    val messageType = part.jsonObject["type"]?.jsonPrimitive?.content ?: continue
                    if (messageType !in supportedTypes) continue
                    val source = part.jsonObject["source"]?.jsonObject ?: continue
                    addAll(parseSource(messageType, source).map { MediaContentPart(it) })
                }
            }
        }
        return MediaContent(parts)
    }

    private fun parseSource(messageType: String, source: JsonObject): List<Resource> {
        return when (source["type"]?.jsonPrimitive?.content) {
            "url" -> listOfNotNull(parseUrl(messageType, source))
            "base64" -> listOfNotNull(parseBase64(messageType, source))
            "content" -> parseContent(messageType, source)
            else -> emptyList()
        }
    }

    private fun parseUrl(messageType: String, source: JsonObject): Resource.Url? {
        val url = source["url"]?.jsonPrimitive?.content
        if (url == null) { logger.warn { "Message '$messageType' has no URL source" }; return null }
        return Resource.Url(url)
    }

    private fun parseBase64(messageType: String, source: JsonObject): Resource.Base64? {
        val data = source["data"]?.jsonPrimitive?.content
        val mediaType = source["media_type"]?.jsonPrimitive?.content
        if (data == null || mediaType == null) {
            logger.warn { "Message '$messageType' missing 'data' or 'media_type'" }
            return null
        }
        return Resource.Base64(data, mediaType)
    }

    private fun parseContent(messageType: String, source: JsonObject): List<Resource> {
        val content = source["content"]
        if (content == null || content !is JsonArray) {
            logger.warn { "Message '$messageType' has no content source" }
            return emptyList()
        }
        return buildList {
            for (param in content.jsonArray) {
                if (param.jsonObject["type"]?.jsonPrimitive?.content == "image") {
                    val imageSource = param.jsonObject["source"]?.jsonObject ?: continue
                    addAll(parseSource(messageType, imageSource))
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------

    private val extractor: MediaContentExtractor = MediaContentExtractorImpl()

    private val mappedMessagesRequestAttributes = listOf(
        "temperature", "model", "max_tokens", "metadata", "service_tier",
        "system", "top_k", "top_p", "stop_sequences", "thinking", "messages", "tools"
    )

    private val mappedMessagesResponseAttributes = listOf(
        "id", "type", "role", "model", "content", "stop_reason", "usage"
    )

    private val logger = KotlinLogging.logger {}
}
