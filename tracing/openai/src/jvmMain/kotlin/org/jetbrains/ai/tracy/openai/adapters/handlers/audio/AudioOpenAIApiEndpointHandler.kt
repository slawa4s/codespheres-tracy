/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Audio API endpoints.
 *
 * Supports:
 * - `POST /v1/audio/transcriptions` — Transcribe audio to text (Whisper)
 * - `POST /v1/audio/translations` — Translate audio to English
 *
 * Extracts multipart form-data fields (model, response_format, timestamp_granularities, audio
 * file metadata) as request span attributes and parses JSON response fields (duration, language,
 * words count) as response span attributes.
 *
 * See [OpenAI Audio API](https://platform.openai.com/docs/api-reference/audio)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operationName = detectOperationName(request.url)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
        span.setAttribute("openai.api.type", "audio")

        val body = request.body.asFormData() ?: return

        val granularities = mutableListOf<String>()

        for (part in body.parts) {
            val charset = part.contentType?.charset() ?: Charsets.UTF_8
            when (part.name) {
                "model" -> {
                    span.setAttribute(GEN_AI_REQUEST_MODEL, part.content.toString(charset))
                }

                "response_format" -> {
                    val responseFormat = part.content.toString(charset)
                    span.setAttribute("tracy.request.response_format", responseFormat)
                    val outputType = when (responseFormat.lowercase()) {
                        "json", "verbose_json" -> "json"
                        "text", "srt", "vtt" -> "text"
                        else -> responseFormat
                    }
                    span.setAttribute("gen_ai.output.type", outputType)
                }

                "timestamp_granularities[]" -> {
                    granularities.add(part.content.toString(charset))
                }

                "file" -> {
                    span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                    val filename = part.filename
                    if (filename != null) {
                        val format = filename.substringAfterLast('.', "").lowercase()
                        if (format.isNotEmpty()) {
                            span.setAttribute("tracy.request.audio.format", format)
                        }
                    }
                }

                else -> {
                    logger.debug { "Unhandled audio form part: ${part.name}" }
                }
            }
        }

        if (granularities.isNotEmpty()) {
            span.setAttribute("tracy.request.timestamp_granularities", granularities.joinToString(","))
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute("tracy.response.transcription.duration_seconds", it)
        }
        body["language"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.transcription.language", it)
        }
        (body["words"] as? JsonArray)?.let { words ->
            span.setAttribute("tracy.response.transcription.words.count", words.size.toLong())
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.debug { "Audio API streaming events received but not yet supported" }
    }

    /**
     * Detects the operation name based on the URL path segments.
     *
     * - `/v1/audio/transcriptions` → `"audio.transcription"`
     * - `/v1/audio/translations` → `"audio.translation"`
     */
    internal fun detectOperationName(url: TracyHttpUrl): String {
        return when {
            url.pathSegments.contains("translations") -> "audio.translation"
            else -> "audio.transcription"
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
