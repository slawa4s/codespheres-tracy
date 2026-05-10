/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractorImpl
import org.jetbrains.ai.tracy.core.http.protocol.*
import org.jetbrains.ai.tracy.openai.adapters.handlers.*
import org.jetbrains.ai.tracy.openai.adapters.handlers.images.ImagesCreateEditOpenAIApiEndpointHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.images.ImagesCreateOpenAIApiEndpointHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.videos.VideosOpenAIApiEndpointHandler
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiSystemIncubatingValues
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap


/**
 * Detects which OpenAI API is being used based on the URL path segments.
 */
private enum class OpenAIApiType(val route: String) {
    // See: https://platform.openai.com/docs/api-reference/embeddings
    EMBEDDINGS("embeddings"),

    // See: https://platform.openai.com/docs/api-reference/audio
    AUDIO("audio"),

    // See: https://platform.openai.com/docs/api-reference/files
    FILES("files"),

    // See: https://platform.openai.com/docs/api-reference/batch
    BATCHES("batches"),

    // See: https://platform.openai.com/docs/api-reference/models
    MODELS("models"),

    // See: https://platform.openai.com/docs/api-reference/moderations
    MODERATIONS("moderations"),

    // See: https://platform.openai.com/docs/api-reference/conversations
    CONVERSATIONS("conversations"),

    // See: https://platform.openai.com/docs/api-reference/images/create
    IMAGES_GENERATIONS("images/generations"),

    // See: https://platform.openai.com/docs/api-reference/images/createEdit
    IMAGES_EDITS("images/edits"),

    // See: https://platform.openai.com/docs/api-reference/videos
    VIDEOS("videos"),

    // See: https://platform.openai.com/docs/api-reference/responses
    RESPONSES_API("responses"),

    // See: https://platform.openai.com/docs/api-reference/completions
    CHAT_COMPLETIONS("completions");

    companion object {
        fun detect(url: TracyHttpUrl): OpenAIApiType? {
            val route = url.pathSegments.joinToString(separator = "/")
            return entries.firstOrNull { route.contains(it.route) }
        }
    }
}

/**
 * Tracing adapter for OpenAI API.
 *
 * Automatically detects and handles multiple OpenAI API endpoints including chat completions,
 * responses API, image operations (generation, editing), audio, embeddings, files, batches,
 * models, moderations, conversations, and video generation.
 *
 * See: [OpenAI API Reference](https://platform.openai.com/docs/api-reference)
 */
class OpenAILLMTracingAdapter : LLMTracingAdapter(genAISystem = GenAiSystemIncubatingValues.OPENAI) {
    private val handlers = ConcurrentHashMap<OpenAIApiType, EndpointApiHandler>()

    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {
        setRoutingAttributes(span, request.url, request.method)
        val handler = handlerFor(request.url)
        handler.handleRequestAttributes(span, request)
    }

    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {
        val handler = handlerFor(response.url)
        OpenAIApiUtils.setCommonResponseAttributes(span, response)
        handler.handleResponseAttributes(span, response)
    }

    override fun getSpanName(request: TracyHttpRequest) = "OpenAI-generation"

    override fun isStreamingRequest(request: TracyHttpRequest): Boolean {
        return when (request.body) {
            is TracyHttpRequestBody.FormData -> {
                val data = request.body.asFormData() ?: return false
                data.parts.filter { it.name == "stream" }.any {
                    val value = it.content.toString(it.contentType?.charset() ?: Charsets.UTF_8)
                    value.toBooleanStrictOrNull() ?: false
                }
            }
            is TracyHttpRequestBody.Json -> {
                val body = request.body.asJson()?.jsonObject ?: return false
                body["stream"]?.jsonPrimitive?.boolean ?: false
            }
            is TracyHttpRequestBody.Empty -> false
        }
    }

    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {
        val handler = handlerFor(url)
        handler.handleStreaming(span, events)
    }

    /**
     * Sets gen_ai.operation.name and openai.api.type attributes based on URL path and HTTP method.
     */
    private fun setRoutingAttributes(span: Span, url: TracyHttpUrl, method: String) {
        val segments = url.pathSegments
        val path = segments.joinToString("/")
        val m = method.uppercase()

        val (operationName, apiType) = detectRouting(segments, path, m)
        if (operationName != null) span.setAttribute("gen_ai.operation.name", operationName)
        if (apiType != null) span.setAttribute("openai.api.type", apiType)
    }

    private fun detectRouting(segments: List<String>, path: String, method: String): Pair<String?, String?> {
        return when {
            // Audio
            path.contains("audio/speech") -> "audio.speech" to "audio"
            path.contains("audio/transcriptions") -> "audio.transcription" to "audio"
            path.contains("audio/translations") -> "audio.translation" to "audio"

            // Embeddings
            path.contains("embeddings") -> "embeddings" to null

            // Files
            path.contains("files") -> detectFilesRouting(segments, method)

            // Batches
            path.contains("batches") -> detectBatchesRouting(segments, method)

            // Models
            path.contains("models") -> detectModelsRouting(segments, method)

            // Moderations
            path.contains("moderations") -> "moderations" to "moderations"

            // Conversations
            path.contains("conversations") -> detectConversationsRouting(segments, method) to "conversations"

            // Images
            path.contains("images/generations") -> "generate_content" to null
            path.contains("images/edits") -> "generate_content" to null

            // Videos
            path.contains("videos") -> detectVideosRouting(segments, method)

            // Responses API
            path.contains("responses") -> detectResponsesRouting(segments, method)

            // Chat completions
            path.contains("completions") -> {
                val idx = segments.indexOf("completions")
                val hasId = segments.size > idx + 1 && segments[idx + 1].isNotBlank()
                if (hasId && method == "GET") "chat.completions.retrieve" to "chat_completions"
                else "chat" to "chat_completions"
            }

            else -> null to null
        }
    }

    private fun detectFilesRouting(segments: List<String>, method: String): Pair<String, String> {
        val idx = segments.indexOf("files")
        val hasId = idx >= 0 && segments.size > idx + 1 && segments[idx + 1].isNotBlank()
        val hasContent = segments.contains("content")
        return when {
            method == "POST" && !hasId -> "files.create" to "files"
            method == "GET" && hasId && hasContent -> "files.content" to "files"
            method == "GET" && hasId -> "files.retrieve" to "files"
            method == "GET" && !hasId -> "files.list" to "files"
            method == "DELETE" && hasId -> "files.delete" to "files"
            else -> "files.create" to "files"
        }
    }

    private fun detectBatchesRouting(segments: List<String>, method: String): Pair<String, String> {
        val idx = segments.indexOf("batches")
        val hasId = idx >= 0 && segments.size > idx + 1 && segments[idx + 1].isNotBlank()
        val hasCancel = segments.contains("cancel")
        return when {
            method == "POST" && !hasId -> "batches.create" to "batches"
            method == "GET" && !hasId -> "batches.list" to "batches"
            method == "GET" && hasId -> "batches.retrieve" to "batches"
            method == "POST" && hasCancel -> "batches.cancel" to "batches"
            else -> "batches.create" to "batches"
        }
    }

    private fun detectModelsRouting(segments: List<String>, method: String): Pair<String?, String?> {
        val idx = segments.indexOf("models")
        val hasId = idx >= 0 && segments.size > idx + 1 && segments[idx + 1].isNotBlank()
        return when {
            method == "GET" && !hasId -> "models.list" to null
            method == "GET" && hasId -> "models.retrieve" to null
            method == "DELETE" && hasId -> "models.delete" to null
            else -> "models.list" to null
        }
    }

    private fun detectConversationsRouting(segments: List<String>, method: String): String {
        val idx = segments.indexOf("conversations")
        val hasConvId = idx >= 0 && segments.size > idx + 1 && segments[idx + 1].isNotBlank()
        val hasItems = segments.contains("items")
        val itemsIdx = segments.indexOf("items")
        val hasItemId = hasItems && itemsIdx >= 0 && segments.size > itemsIdx + 1 && segments[itemsIdx + 1].isNotBlank()

        return when {
            hasItems && hasItemId && method == "GET" -> "conversations.items.retrieve"
            hasItems && hasItemId && method == "DELETE" -> "conversations.items.delete"
            hasItems && method == "GET" -> "conversations.items.list"
            hasItems && method == "POST" -> "conversations.items.create"
            !hasConvId && method == "POST" -> "conversations.create"
            !hasConvId && method == "GET" -> "conversations.list"
            hasConvId && method == "GET" -> "conversations.retrieve"
            hasConvId && (method == "POST" || method == "PATCH") -> "conversations.update"
            hasConvId && method == "DELETE" -> "conversations.delete"
            else -> "conversations.create"
        }
    }

    private fun detectVideosRouting(segments: List<String>, method: String): Pair<String?, String?> {
        val idx = segments.indexOf("videos")
        val hasId = idx >= 0 && segments.size > idx + 1 && segments[idx + 1].isNotBlank()
        return when {
            method == "POST" && !hasId -> "videos.create" to null
            method == "GET" && hasId -> "videos.retrieve" to null
            method == "GET" && !hasId -> "videos.list" to null
            method == "DELETE" && hasId -> "videos.delete" to null
            else -> "videos.create" to null
        }
    }

    private fun detectResponsesRouting(segments: List<String>, method: String): Pair<String, String> {
        val idx = segments.indexOf("responses")
        val hasId = idx >= 0 && segments.size > idx + 1 && segments[idx + 1].isNotBlank()
        val lastSegment = segments.lastOrNull()
        return when {
            method == "POST" && !hasId -> "generate_content" to "responses"
            method == "GET" && hasId && lastSegment == "input_items" -> "response.input_items.list" to "responses"
            method == "GET" && hasId -> "response.retrieve" to "responses"
            method == "POST" && lastSegment == "cancel" -> "response.cancel" to "responses"
            method == "POST" && lastSegment == "input_tokens" -> "response.input_tokens.count" to "responses"
            method == "POST" && lastSegment == "compact" -> "response.compact" to "responses"
            method == "DELETE" && hasId -> "response.delete" to "responses"
            else -> "generate_content" to "responses"
        }
    }

    /**
     * Determines the appropriate handler for an OpenAI API based on the given URL.
     */
    private fun handlerFor(endpoint: TracyHttpUrl): EndpointApiHandler {
        val apiType = OpenAIApiType.detect(endpoint)
        val extractor = MediaContentExtractorImpl()

        return when (apiType) {
            OpenAIApiType.EMBEDDINGS -> handlers.getOrPut(OpenAIApiType.EMBEDDINGS) {
                EmbeddingsOpenAIApiEndpointHandler()
            }

            OpenAIApiType.AUDIO -> handlers.getOrPut(OpenAIApiType.AUDIO) {
                AudioOpenAIApiEndpointHandler()
            }

            OpenAIApiType.FILES -> handlers.getOrPut(OpenAIApiType.FILES) {
                FilesOpenAIApiEndpointHandler()
            }

            OpenAIApiType.BATCHES -> handlers.getOrPut(OpenAIApiType.BATCHES) {
                BatchesOpenAIApiEndpointHandler()
            }

            OpenAIApiType.MODELS -> handlers.getOrPut(OpenAIApiType.MODELS) {
                ModelsOpenAIApiEndpointHandler()
            }

            OpenAIApiType.MODERATIONS -> handlers.getOrPut(OpenAIApiType.MODERATIONS) {
                ModerationsOpenAIApiEndpointHandler()
            }

            OpenAIApiType.CONVERSATIONS -> handlers.getOrPut(OpenAIApiType.CONVERSATIONS) {
                ConversationsOpenAIApiEndpointHandler()
            }

            OpenAIApiType.CHAT_COMPLETIONS -> handlers.getOrPut(OpenAIApiType.CHAT_COMPLETIONS) {
                ChatCompletionsOpenAIApiEndpointHandler(extractor)
            }

            OpenAIApiType.RESPONSES_API -> handlers.getOrPut(OpenAIApiType.RESPONSES_API) {
                ResponsesOpenAIApiEndpointHandler(extractor)
            }

            OpenAIApiType.IMAGES_GENERATIONS -> handlers.getOrPut(OpenAIApiType.IMAGES_GENERATIONS) {
                ImagesCreateOpenAIApiEndpointHandler(extractor)
            }

            OpenAIApiType.IMAGES_EDITS -> handlers.getOrPut(OpenAIApiType.IMAGES_EDITS) {
                ImagesCreateEditOpenAIApiEndpointHandler(extractor)
            }

            OpenAIApiType.VIDEOS -> handlers.getOrPut(OpenAIApiType.VIDEOS) {
                VideosOpenAIApiEndpointHandler(extractor)
            }

            null -> handlers.getOrPut(OpenAIApiType.CHAT_COMPLETIONS) {
                logger.warn { "Unknown OpenAI API detected. Defaulting to 'chat completion'." }
                ChatCompletionsOpenAIApiEndpointHandler(extractor)
            }
        }
    }

    private val logger = KotlinLogging.logger {}
}
