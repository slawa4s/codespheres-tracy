/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles OpenAI Audio Transcriptions API (`POST /v1/audio/transcriptions`).
 *
 * Extracts span attributes from multipart/form-data requests and JSON responses.
 * Sets `openai.api.type = "audio"` and `gen_ai.operation.name = "audio.transcription"` on every span.
 *
 * Request attributes extracted:
 * - `gen_ai.request.model` — model name (e.g. `whisper-1`)
 * - `tracy.request.audio.size_bytes` — byte length of the uploaded audio file
 * - `tracy.request.audio.format` — audio format derived from filename extension or content-type subtype
 * - `tracy.request.response_format` — requested response format (e.g. `json`, `verbose_json`, `text`)
 * - `gen_ai.output.type` — `"json"` for `json`/`verbose_json` formats, `"text"` otherwise
 * - `tracy.request.timestamp_granularities` — comma-joined list of requested granularities
 *
 * Response attributes extracted (verbose_json format):
 * - `tracy.response.transcription.duration_seconds` — audio duration in seconds
 * - `tracy.response.transcription.language` — detected or specified language
 * - `tracy.response.transcription.words.count` — number of word-level segments returned
 *
 * See [OpenAI Audio Transcriptions](https://platform.openai.com/docs/api-reference/audio/createTranscription)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "audio")
        span.setAttribute(GEN_AI_OPERATION_NAME, "audio.transcription")

        val body = request.body.asFormData() ?: return
        val granularities = mutableListOf<String>()

        for (part in body.parts) {
            when (part.name) {
                "file" -> {
                    span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                    val format = part.filename
                        ?.takeIf { it.contains('.') }
                        ?.substringAfterLast('.')
                        ?: part.contentType?.subtype
                        ?: "unknown"
                    span.setAttribute("tracy.request.audio.format", format)
                }
                "model" -> {
                    span.setAttribute(GEN_AI_REQUEST_MODEL, part.content.toString(Charsets.UTF_8))
                }
                "response_format" -> {
                    val value = part.content.toString(Charsets.UTF_8)
                    span.setAttribute("tracy.request.response_format", value)
                    val outputType = if (value == "json" || value == "verbose_json") "json" else "text"
                    span.setAttribute(GEN_AI_OUTPUT_TYPE, outputType)
                }
                "timestamp_granularities[]" -> {
                    granularities.add(part.content.toString(Charsets.UTF_8))
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
        body["words"]?.jsonArray?.size?.let {
            span.setAttribute("tracy.response.transcription.words.count", it.toLong())
        }
    }

    /** Audio transcription does not emit SSE events; streaming is not supported for this endpoint. */
    override fun handleStreaming(span: Span, events: String) = Unit
}
