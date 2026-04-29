/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.common.AttributeKey
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
 * Handler for OpenAI Audio Transcription and Translation API.
 *
 * Handles multipart form-data requests for:
 * - `/v1/audio/transcriptions` (operationName = "audio.transcription")
 * - `/v1/audio/translations` (operationName = "audio.translation")
 *
 * Extracts audio file metadata (size, format), model, and response_format from the request,
 * and transcription/translation text from the response.
 *
 * See [Audio API Reference](https://platform.openai.com/docs/api-reference/audio)
 */
internal class AudioOpenAIApiEndpointHandler(
    private val operationName: String
) : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)

        val body = request.body.asFormData() ?: return

        for (part in body.parts) {
            val contentType = part.contentType

            when {
                part.name == "model" -> {
                    val content = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute(GEN_AI_REQUEST_MODEL, content)
                }

                part.name == "response_format" -> {
                    val content = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute("gen_ai.request.response_format", content)
                }

                contentType?.type == "audio" || part.name == "file" -> {
                    // Audio file part: extract byte size and format
                    span.setAttribute(
                        AttributeKey.longKey("gen_ai.request.audio.size_bytes"),
                        part.content.size.toLong()
                    )

                    val filename = part.filename
                    val format = when {
                        contentType != null && contentType.type == "audio" -> contentType.subtype
                        filename != null -> filename
                            .substringAfterLast('.', "")
                            .takeIf { it.isNotEmpty() }
                        else -> null
                    }
                    if (format != null) {
                        span.setAttribute("gen_ai.request.audio.format", format)
                    }
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute(AttributeKey.longKey("http.status_code"), response.code.toLong())

        val body = response.body.asJson()?.jsonObject ?: return
        body["text"]?.let {
            span.setAttribute("gen_ai.response.text", it.jsonPrimitive.content)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.debug { "Audio API does not use server-sent events streaming" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
