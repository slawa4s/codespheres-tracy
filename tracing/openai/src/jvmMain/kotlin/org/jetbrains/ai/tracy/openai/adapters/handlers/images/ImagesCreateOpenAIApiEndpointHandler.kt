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
import org.jetbrains.ai.tracy.openai.adapters.handlers.asString
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
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
        val body = request.body.asJson()?.jsonObject ?: return

        span.setAttribute(GEN_AI_OUTPUT_TYPE, "image")
        body["prompt"]?.let { span.setAttribute("gen_ai.prompt.0.content", it.jsonPrimitive.content.orRedactedInput()) }
        body["model"]?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.jsonPrimitive.content) }
        body["stream"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("gen_ai.request.stream", it) }
        body["n"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.request.n", it.toLong()) }
        body["partial_images"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.request.partial_images", it.toLong()) }

        val manuallyParsedKeys = listOf("prompt", "model", "stream", "n", "partial_images")
        for ((key, value) in body.entries) {
            if (key in manuallyParsedKeys) {
                continue
            }
            span.setAttribute("tracy.request.$key", value.asString.orRedactedInput())
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