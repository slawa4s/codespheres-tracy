/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the OpenAI Audio Transcriptions API (`/v1/audio/transcriptions`).
 *
 * Parses multipart form-data upload requests and `verbose_json` / `json` transcription responses.
 *
 * See [OpenAI Audio Transcriptions API](https://platform.openai.com/docs/api-reference/audio/createTranscription)
 */
internal class AudioTranscriptionOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gen_ai.operation.name", "audio.transcription")
        span.setAttribute("openai.api.type", "audio")

        val body = request.body.asFormData() ?: return

        val granularities = mutableListOf<String>()

        for (part in body.parts) {
            val charset = part.contentType?.charset() ?: Charsets.UTF_8

            when (part.name) {
                "file" -> {
                    span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                    val format = part.filename
                        ?.substringAfterLast(".", "")
                        ?.takeIf { it.isNotEmpty() }
                        ?: part.contentType?.subtype
                    if (format != null) {
                        span.setAttribute("tracy.request.audio.format", format)
                    }
                }

                "model" -> {
                    val value = part.content.toString(charset)
                    span.setAttribute(GEN_AI_REQUEST_MODEL, value)
                }

                "response_format" -> {
                    val value = part.content.toString(charset)
                    span.setAttribute("tracy.request.response_format", value)
                    val outputType = when (value) {
                        "verbose_json", "json" -> "json"
                        "text" -> "text"
                        else -> null
                    }
                    if (outputType != null) {
                        span.setAttribute("gen_ai.output.type", outputType)
                    }
                }

                "timestamp_granularities[]" -> {
                    granularities.add(part.content.toString(charset))
                }

                null -> logger.warn { "Audio transcription form part with missing name ignored" }
            }
        }

        if (granularities.isNotEmpty()) {
            span.setAttribute("tracy.request.timestamp_granularities", granularities.joinToString(","))
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["language"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.transcription.language", it)
        }

        body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute("tracy.response.transcription.duration_seconds", it)
        }

        body["words"]?.jsonArray?.let {
            span.setAttribute("tracy.response.transcription.words.count", it.size.toLong())
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
