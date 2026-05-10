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
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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

        span.setAttribute(GEN_AI_OPERATION_NAME, "generate_content")

        body["prompt"]?.let { span.setAttribute("gen_ai.prompt.0.content", it.jsonPrimitive.content.orRedactedInput()) }
        body["model"]?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.jsonPrimitive.content) }
        body["size"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.size", it) }
        body["n"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.request.n", it) }
        body["response_format"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.response_format", it) }
        body["quality"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.quality", it) }
        body["output_format"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.output_format", it) }
        body["background"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.background", it) }
        body["partial_images"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.request.partial_images", it) }
        body["stream"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("gen_ai.request.stream", it) }

        val manuallyParsedKeys = listOf("prompt", "model", "stream")
        for ((key, value) in body.entries) {
            if (key in manuallyParsedKeys) {
                continue
            }
            span.setAttribute("gen_ai.request.$key", value.asString.orRedactedInput())
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        handleImageGenerationResponseAttributes(span, response, extractor)

        val body = response.body.asJson()?.jsonObject ?: return
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "image")
        body["created"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.created", it) }
        body["created_at"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.created_at", it) }
        body["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?.let { span.setAttribute("tracy.response.image.url", it) }
        body["stream"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("gen_ai.request.stream", it) }
        body["model"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it) }
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