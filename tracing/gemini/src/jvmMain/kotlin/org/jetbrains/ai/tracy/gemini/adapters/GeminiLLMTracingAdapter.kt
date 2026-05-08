/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractorImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiCachedContentsHandler
import org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiContentGenHandler
import org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiImagenHandler
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*

/**
 * Tracing adapter for Google Gemini and Imagen APIs.
 *
 * Handles tracing for:
 * - **Gemini content generation** (`/v1beta/models/{model}:generateContent`): text, chat, tool calling, multimodal
 * - **Gemini embeddings** (`/v1beta/models/{model}:embedContent`, `:batchEmbedContents`): single and batch embeddings
 * - **Gemini token counting** (`/v1beta/models/{model}:countTokens`): token count responses
 * - **Cached contents** (`/v1beta/cachedContents`): create, get, update, delete, list
 * - **Imagen** (`/v1/models/imagen*:predict`): image generation and editing
 *
 * Automatically selects the appropriate endpoint handler based on the request URL and detects
 * the `gemini.api.type` attribute ("models" or "cachedContents") from the URL.
 *
 * ## Example Usage
 * ```kotlin
 * val client = instrument(HttpClient(), GeminiLLMTracingAdapter())
 *
 * // Gemini content generation
 * client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent") {
 *     setBody("""{"contents": [{"parts": [{"text": "Hello!"}]}]}""")
 * }
 *
 * // Imagen image generation
 * client.post("https://us-central1-aiplatform.googleapis.com/v1/models/imagen-4.0-generate-001:predict") {
 *     setBody("""{"instances": [{"prompt": "A robot"}], "parameters": {"sampleCount": 3}}""")
 * }
 * ```
 *
 * See: [Gemini API](https://ai.google.dev/gemini-api/docs), [Imagen API](https://cloud.google.com/vertex-ai/docs/generative-ai/image/overview)
 */
class GeminiLLMTracingAdapter : LLMTracingAdapter(genAISystem = GenAiSystemIncubatingValues.GEMINI) {
    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {
        val apiType = detectApiType(request.url)
        span.setAttribute("gemini.api.type", apiType)

        if (apiType == "cachedContents") {
            val operation = detectCacheOperation(request.url, request.method)
            span.setAttribute(GEN_AI_OPERATION_NAME, operation)
            cachedContentsHandler.handleRequestAttributes(span, request)
        } else {
            val (model, operation) = request.url.modelAndOperation()
            model?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, model) }
            operation?.let { span.setAttribute(GEN_AI_OPERATION_NAME, operation) }

            val handler = selectContentHandler(request.url)
            handler.handleRequestAttributes(span, request)
        }
    }

    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {
        val apiType = detectApiType(response.url)

        if (apiType == "cachedContents") {
            val operation = detectCacheOperation(response.url, response.requestMethod)
            span.setAttribute(GEN_AI_OPERATION_NAME, operation)
            cachedContentsHandler.handleResponseAttributes(span, response)
        } else {
            val (_, operation) = response.url.modelAndOperation()
            val outputType = when (operation) {
                "generateContent" -> "message"
                "embedContent", "batchEmbedContents" -> "embedding"
                "predict" -> "image"
                else -> null
            }
            outputType?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it) }

            val handler = selectContentHandler(response.url)
            handler.handleResponseAttributes(span, response)
        }
    }

    override fun getSpanName(request: TracyHttpRequest) = "Gemini-generation"

    // streaming is not supported
    override fun isStreamingRequest(request: TracyHttpRequest) = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {
        val handler = selectContentHandler(url)
        handler.handleStreaming(span, events)
    }

    /** Returns "cachedContents" for cachedContents API URLs, "models" otherwise. */
    private fun detectApiType(url: TracyHttpUrl): String {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        return if (segments.contains("cachedContents")) "cachedContents" else "models"
    }

    /** Determines the operation name for cachedContents endpoints from HTTP method and URL. */
    private fun detectCacheOperation(url: TracyHttpUrl, method: String): String {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val cacheIdx = segments.indexOf("cachedContents")
        val hasCacheId = cacheIdx >= 0 && segments.size > cacheIdx + 1
        return when {
            !hasCacheId && method == "GET" -> "cachedContents.list"
            !hasCacheId -> "cachedContents.create"
            method == "DELETE" -> "cachedContents.delete"
            method == "PATCH" -> "cachedContents.update"
            else -> "cachedContents.get"
        }
    }

    private fun selectContentHandler(url: TracyHttpUrl): EndpointApiHandler = when {
        url.isImagenUrl() -> imagenHandler
        else -> contentGenHandler
    }

    private fun TracyHttpUrl.modelAndOperation(): Pair<String?, String?> {
        // url ends with `[model]:[operation]`
        return this.pathSegments.lastOrNull()?.split(":")
            ?.let { it.firstOrNull() to it.lastOrNull() } ?: (null to null)
    }

    private fun TracyHttpUrl.isImagenUrl(): Boolean {
        val (model, operation) = this.modelAndOperation()
        return (model?.startsWith("imagen") == true) && (operation == "predict")
    }

    private companion object {
        private val extractor: MediaContentExtractor = MediaContentExtractorImpl()
        private val contentGenHandler = GeminiContentGenHandler(extractor)
        private val imagenHandler = GeminiImagenHandler(extractor)
        private val cachedContentsHandler = GeminiCachedContentsHandler()
    }
}
