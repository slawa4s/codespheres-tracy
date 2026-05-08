/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

/**
 * Extracts request/response attributes for the OpenAI Audio Transcriptions API.
 *
 * See [Audio Transcriptions API](https://platform.openai.com/docs/api-reference/audio/createTranscription)
 */
internal class AudioTranscriptionOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)

        val body = request.body.asFormData() ?: return

        val timestampGranularities = mutableListOf<String>()

        for (part in body.parts) {
            val contentType = part.contentType
            val partName = part.name ?: continue

            when {
                // Audio file part: extract filename, size, and format
                partName == "file" -> {
                    span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                    // Determine audio format from content-type or filename extension
                    val format = contentType?.subtype
                        ?: part.filename?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
                    if (format != null) {
                        span.setAttribute("tracy.request.audio.format", format)
                    }
                }

                partName == "model" -> {
                    val content = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute(GEN_AI_REQUEST_MODEL, content)
                }

                partName == "response_format" -> {
                    val content = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute("tracy.request.response_format", content)
                    // Set output type for json/verbose_json formats
                    if (content == "json" || content == "verbose_json") {
                        span.setAttribute(GEN_AI_OUTPUT_TYPE, "json")
                    }
                }

                // timestamp_granularities[] parts are submitted as array form fields
                partName == "timestamp_granularities[]" || partName == "timestamp_granularities" -> {
                    val content = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    timestampGranularities.add(content)
                }

                else -> {
                    logger.trace { "Unhandled audio transcription form part: '$partName'" }
                }
            }
        }

        if (timestampGranularities.isNotEmpty()) {
            span.setAttribute("tracy.request.timestamp_granularities", timestampGranularities.joinToString(","))
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_RESPONSE_MODEL, it)
        }
        body["language"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.transcription.language", it)
        }
        body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute("tracy.response.transcription.duration_seconds", it)
        }
        body["words"]?.jsonArray?.let { words ->
            span.setAttribute("tracy.response.transcription.words.count", words.size.toLong())
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio transcription endpoint does not support SSE streaming
    }

    companion object {
        private const val OPERATION_NAME = "audio.transcription"
        private val logger = KotlinLogging.logger {}
    }
}
