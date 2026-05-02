/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Audio API endpoints:
 * - POST /v1/audio/transcriptions
 * - POST /v1/audio/translations
 *
 * Parses multipart/form-data requests to extract the model, response format,
 * audio file size, and audio format information. Parses JSON responses to
 * extract the transcription or translation text.
 *
 * See [Audio API Reference](https://platform.openai.com/docs/api-reference/audio)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val pathSegments = request.url.pathSegments
        val operationName = when {
            pathSegments.contains("transcriptions") -> "audio.transcription"
            pathSegments.contains("translations") -> "audio.translation"
            else -> null
        }
        operationName?.let { span.setAttribute(GEN_AI_OPERATION_NAME, it) }

        val body = request.body.asFormData() ?: return

        for (part in body.parts) {
            val contentType = part.contentType
            when (part.name) {
                "model" -> {
                    val text = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute(GEN_AI_REQUEST_MODEL, text)
                }
                "response_format" -> {
                    val text = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute("gen_ai.request.response_format", text)
                }
                "file" -> {
                    span.setAttribute("gen_ai.request.audio.size_bytes", part.content.size.toLong())

                    // Derive format from the content-type subtype (e.g., audio/mp3 → "mp3"),
                    // falling back to the filename extension when content-type is absent.
                    val format = if (contentType != null && contentType.type == "audio") {
                        contentType.subtype
                    } else {
                        part.filename
                            ?.substringAfterLast('.')
                            ?.takeIf { it.isNotEmpty() && it != part.filename }
                    }
                    format?.let { span.setAttribute("gen_ai.request.audio.format", it) }
                }
                null -> logger.warn { "Form data part with missing name ignored. Content type: '$contentType'" }
                else -> {
                    // Other parts (language, prompt, temperature, timestamp_granularities, etc.) are not traced.
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["text"]?.jsonPrimitive?.content?.let { text ->
            span.setAttribute("gen_ai.response.text", text)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio transcription/translation API does not use server-sent events streaming.
        logger.warn { "Audio transcription/translation API does not use server-sent events streaming" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
