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
import kotlinx.serialization.json.longOrNull
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
 * Extracts span attributes from requests sent to:
 * - `POST /v1/audio/transcriptions` — sets `gen_ai.operation.name` = `"audio.transcription"` (multipart FormData)
 * - `POST /v1/audio/translations` — sets `gen_ai.operation.name` = `"audio.translation"` (multipart FormData)
 * - `POST /v1/audio/speech` — sets `gen_ai.operation.name` = `"audio.speech"` (JSON body)
 *
 * ## Request attributes extracted (transcriptions/translations — multipart FormData)
 * - `gen_ai.request.model` — from the `model` field
 * - `tracy.request.response_format` — from the `response_format` field
 * - `gen_ai.output.type` — set to `"json"` when `response_format` is `"json"` or `"verbose_json"`
 * - `tracy.request.timestamp_granularities` — comma-joined values of all `timestamp_granularities[]` fields
 * - `tracy.request.temperature` — from the `temperature` field
 * - `tracy.request.prompt.present` — `true` when a `prompt` field is present
 * - `tracy.request.audio.size_bytes` — byte length of the uploaded `file` part
 * - `tracy.request.audio.format` — audio format derived from the `file` part's content-type subtype or filename extension
 *
 * ## Request attributes extracted (speech — JSON body)
 * - `gen_ai.request.model` — from the `model` field
 * - `tracy.request.voice` — from the `voice` field
 * - `tracy.request.response_format` — from the `response_format` field
 * - `tracy.request.speed` — from the `speed` field
 * - `gen_ai.output.type` — set to `"speech"`
 *
 * ## Response attributes extracted (from verbose_json or plain json body)
 * - `tracy.response.transcription.duration_seconds` — for transcription requests
 * - `tracy.response.transcription.language` — for transcription requests
 * - `tracy.response.transcription.words.count` — length of `words` array (verbose_json only)
 * - `tracy.response.translation.duration_seconds` — for translation requests
 * - `tracy.response.audio.size_bytes` — byte length of the speech audio response
 *
 * See [Audio API Reference](https://platform.openai.com/docs/api-reference/audio)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "audio")
        span.setAttribute(GEN_AI_OPERATION_NAME, detectOperationName(request.url))

        if (request.url.pathSegments.contains("speech")) {
            handleSpeechRequest(span, request)
            return
        }

        val body = request.body.asFormData() ?: return

        val granularities = mutableListOf<String>()

        for (part in body.parts) {
            val charset = part.contentType?.charset() ?: Charsets.UTF_8
            when (part.name) {
                "model" -> {
                    val text = part.content.toString(charset)
                    span.setAttribute(GEN_AI_REQUEST_MODEL, text)
                }
                "response_format" -> {
                    val text = part.content.toString(charset)
                    span.setAttribute("tracy.request.response_format", text)
                    if (text == "verbose_json" || text == "json") {
                        span.setAttribute(GEN_AI_OUTPUT_TYPE, "json")
                    }
                }
                "timestamp_granularities[]" -> {
                    granularities.add(part.content.toString(charset))
                }
                "temperature" -> {
                    span.setAttribute("tracy.request.temperature", part.content.toString(charset))
                }
                "prompt" -> {
                    span.setAttribute("tracy.request.prompt.present", true)
                }
                "file" -> {
                    span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                    val format = audioFormat(part.contentType?.subtype, part.filename)
                    if (format != null) {
                        span.setAttribute("tracy.request.audio.format", format)
                    }
                }
                null -> logger.warn { "Audio form-data part with missing name ignored" }
            }
        }

        if (granularities.isNotEmpty()) {
            span.setAttribute("tracy.request.timestamp_granularities", granularities.joinToString(","))
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute("openai.api.type", "audio")

        val body = response.body.asJson()?.jsonObject ?: return

        if (response.url.pathSegments.contains("speech")) {
            body["_response_size_bytes"]?.jsonPrimitive?.longOrNull?.let {
                span.setAttribute("tracy.response.audio.size_bytes", it)
            }
            return
        }

        val isTranscription = response.url.pathSegments.contains("transcriptions")

        body["duration"]?.jsonPrimitive?.doubleOrNull?.let { duration ->
            val key = if (isTranscription) "tracy.response.transcription.duration_seconds"
                      else "tracy.response.translation.duration_seconds"
            span.setAttribute(key, duration)
        }

        if (isTranscription) {
            body["language"]?.jsonPrimitive?.content?.let { language ->
                span.setAttribute("tracy.response.transcription.language", language)
            }
            body["words"]?.jsonArray?.let { words ->
                span.setAttribute("tracy.response.transcription.words.count", words.size.toLong())
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Audio API does not use server-sent events streaming" }
    }

    private fun detectOperationName(url: TracyHttpUrl): String {
        val segments = url.pathSegments
        return when {
            segments.contains("transcriptions") -> "audio.transcription"
            segments.contains("translations") -> "audio.translation"
            segments.contains("speech") -> "audio.speech"
            else -> {
                logger.warn { "Unknown audio endpoint path: ${segments.joinToString("/")} — defaulting to audio.transcription" }
                "audio.transcription"
            }
        }
    }

    /**
     * Reads JSON body fields for `POST /v1/audio/speech` and sets the corresponding span attributes.
     */
    private fun handleSpeechRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        body["model"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }
        body["voice"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.request.voice", it) }
        body["response_format"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.request.response_format", it) }
        body["speed"]?.jsonPrimitive?.doubleOrNull?.let { span.setAttribute("tracy.request.speed", it) }
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "speech")
    }

    /**
     * Returns the audio format string: prefers the content-type subtype, falls back to filename extension.
     */
    private fun audioFormat(subtype: String?, filename: String?): String? {
        if (!subtype.isNullOrBlank()) return subtype
        val ext = filename?.substringAfterLast('.', "")
        return ext?.takeIf { it.isNotBlank() }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
