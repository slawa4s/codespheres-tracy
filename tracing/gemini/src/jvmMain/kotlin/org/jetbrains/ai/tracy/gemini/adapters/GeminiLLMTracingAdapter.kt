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
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiContentGenHandler
import org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiEmbedHandler
import org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiImagenHandler
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*
import kotlinx.serialization.json.jsonObject

/**
 * Tracing adapter for Google Gemini and Imagen APIs.
 *
 * Handles tracing for both Gemini content generation (text, chat, tool calling, multimodal) and
 * Imagen image operations (generation, editing, upscaling). Automatically selects the appropriate
 * endpoint handler based on the request URL and model type.
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
        val (model, rawOperation) = request.url.modelAndOperation()

        model?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, model) }

        // Detect embed requests: Vertex AI uses "predict" for embed, remap to Gemini API name
        val isEmbed = model != null && model.contains("embedding") && rawOperation == "predict"
        val isBatchEmbed = isEmbed && request.body.asJson()?.jsonObject?.containsKey("requests") == true
        val operation = when {
            isEmbed && isBatchEmbed -> "batchEmbedContents"
            isEmbed -> "embedContent"
            else -> rawOperation
        }

        operation?.let { span.setAttribute(GEN_AI_OPERATION_NAME, it) }

        // Set gemini.api.type from URL path
        val segments = request.url.pathSegments
        if (segments.dropLast(1).contains("models")) {
            span.setAttribute("gemini.api.type", "models")
        }

        // Set gen_ai.output.type based on operation
        when (operation) {
            "generateContent", "streamGenerateContent" -> span.setAttribute(GEN_AI_OUTPUT_TYPE, "message")
            "embedContent", "batchEmbedContents" -> span.setAttribute(GEN_AI_OUTPUT_TYPE, "embedding")
        }

        val handler = selectHandler(request.url, model, isEmbed)
        handler.handleRequestAttributes(span, request)
    }

    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {
        val (model, rawOperation) = response.url.modelAndOperation()
        val isEmbed = model != null && model.contains("embedding") && rawOperation == "predict"
        val handler = selectHandler(response.url, model, isEmbed)
        handler.handleResponseAttributes(span, response)
    }

    override fun getSpanName(request: TracyHttpRequest) = "Gemini-generation"

    // streaming is not supported
    override fun isStreamingRequest(request: TracyHttpRequest) = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {
        val (model, rawOperation) = url.modelAndOperation()
        val isEmbed = model != null && model.contains("embedding") && rawOperation == "predict"
        val handler = selectHandler(url, model, isEmbed)
        handler.handleStreaming(span, events)
    }

    private fun selectHandler(url: TracyHttpUrl, model: String? = null, isEmbed: Boolean = false): EndpointApiHandler = when {
        url.isImagenUrl() -> GeminiImagenHandler(extractor)
        isEmbed -> GeminiEmbedHandler()
        else -> GeminiContentGenHandler(extractor)
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
    }
}