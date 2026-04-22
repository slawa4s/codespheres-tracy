/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.images

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils
import org.jetbrains.ai.tracy.openai.adapters.handlers.asString
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts request/response bodies of Image Generation API.
 *
 * See [Image Generation API](https://platform.openai.com/docs/api-reference/images/create)
 */
internal class ImagesCreateOpenAIApiEndpointHandler(
    private val extractor: MediaContentExtractor
) : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        // Set model (and temperature) via the common path first so gen_ai.request.model is captured
        // even if the body parsing below returns early.
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        val body = request.body.asJson()?.jsonObject ?: return

        body["prompt"]?.let { span.setAttribute("gen_ai.prompt.0.content", it.jsonPrimitive.content.orRedactedInput()) }
        body["model"]?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.jsonPrimitive.content) }

        // Configuration metadata — not user-supplied content, no redaction needed.
        body["size"]?.let { span.setAttribute("gen_ai.request.size", it.asString) }
        body["n"]?.let { span.setAttribute("gen_ai.request.n", it.asString) }
        body["quality"]?.let { span.setAttribute("gen_ai.request.quality", it.asString) }
        body["style"]?.let { span.setAttribute("gen_ai.request.style", it.asString) }
        body["response_format"]?.let { span.setAttribute("gen_ai.request.response_format", it.asString) }

        val manuallyParsedKeys = listOf("prompt", "model", "size", "n", "quality", "style", "response_format")
        for ((key, value) in body.entries) {
            if (key in manuallyParsedKeys) {
                continue
            }
            span.setAttribute("gen_ai.request.$key", value.asString.orRedactedInput())
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        handleImageGenerationResponseAttributes(span, response, extractor)
    }

    override fun handleStreaming(span: Span, events: String) {
        for (line in events.lineSequence()) {
            if (!line.startsWith("data:")) {
                continue
            }
            val data = Json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject

            handleStreamedImage(
                span, data, extractor,
                completedType = "image_generation.completed",
                partialImageType = "image_generation.partial_image",
            )
        }
    }
}