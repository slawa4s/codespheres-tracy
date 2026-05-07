/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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
 * Handles OpenAI Audio Transcription API endpoint: `POST /v1/audio/transcriptions`.
 *
 * Extracts telemetry from multipart form requests and JSON transcription responses.
 *
 * ## Request attributes extracted:
 * - `openai.api.type`: always `"audio"`
 * - `gen_ai.operation.name`: always `"audio.transcription"`
 * - `gen_ai.request.model`: from `model` form field
 * - `tracy.request.response_format`: from `response_format` form field
 * - `gen_ai.output.type`: derived from `response_format` (`verbose_json`/`json` → `"json"`)
 * - `tracy.request.timestamp_granularities`: from `timestamp_granularities[]` form fields
 * - `tracy.request.audio.size_bytes`: byte size of the uploaded audio file
 * - `tracy.request.audio.format`: format derived from content-type or filename extension
 *
 * ## Response attributes extracted (verbose_json):
 * - `tracy.response.transcription.duration_seconds`: from `duration` field
 * - `tracy.response.transcription.language`: from `language` field
 * - `tracy.response.transcription.words.count`: from length of `words` array
 *
 * See [OpenAI Audio Transcriptions API](https://platform.openai.com/docs/api-reference/audio/createTranscription)
 */
internal class AudioTranscriptionOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "audio")
        span.setAttribute("gen_ai.operation.name", "audio.transcription")

        val body = request.body.asFormData() ?: return

        val timestampGranularities = mutableListOf<String>()

        for (part in body.parts) {
            val charset = part.contentType?.charset() ?: Charsets.UTF_8
            val textContent = when (part.contentType?.type) {
                null, "text" -> part.content.toString(charset)
                else -> null
            }

            when (part.name) {
                "model" -> {
                    if (textContent != null) {
                        span.setAttribute(GEN_AI_REQUEST_MODEL, textContent)
                    }
                }

                "response_format" -> {
                    if (textContent != null) {
                        span.setAttribute("tracy.request.response_format", textContent)
                        // Derive gen_ai.output.type from response_format
                        val outputType = when (textContent) {
                            "json", "verbose_json" -> "json"
                            else -> null
                        }
                        if (outputType != null) {
                            span.setAttribute("gen_ai.output.type", outputType)
                        }
                    }
                }

                "timestamp_granularities[]", "timestamp_granularities" -> {
                    if (textContent != null) {
                        timestampGranularities.add(textContent)
                    }
                }

                "file" -> {
                    span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                    val format = audioFormat(part.contentType?.asString(), part.filename)
                    if (format != null) {
                        span.setAttribute("tracy.request.audio.format", format)
                    }
                }

                null -> {
                    logger.warn { "Audio transcription form part with missing name ignored." }
                }
                // Other fields (language, prompt, temperature, etc.) are silently skipped
            }
        }

        if (timestampGranularities.isNotEmpty()) {
            span.setAttribute(
                "tracy.request.timestamp_granularities",
                timestampGranularities.joinToString(",")
            )
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["duration"]?.jsonPrimitive?.doubleOrNull?.let { duration ->
            span.setAttribute("tracy.response.transcription.duration_seconds", duration)
        }

        body["language"]?.jsonPrimitive?.content?.let { language ->
            span.setAttribute("tracy.response.transcription.language", language)
        }

        body["words"]?.let { wordsElement ->
            try {
                val wordCount = wordsElement.jsonArray.size
                span.setAttribute("tracy.response.transcription.words.count", wordCount.toLong())
            } catch (_: Exception) {
                logger.warn { "Failed to parse 'words' array from transcription response" }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio transcription does not use server-sent events streaming
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Derives the audio format string from the content-type header or filename extension.
         *
         * Content-type takes precedence over filename extension.
         * For example, `audio/mpeg` → `"mp3"`, or `audio/wav` → `"wav"`.
         */
        internal fun audioFormat(contentTypeString: String?, filename: String?): String? {
            // Try to derive from content-type first
            if (contentTypeString != null) {
                val mimeType = contentTypeString.substringBefore(";").trim().lowercase()
                val subtype = mimeType.substringAfter("/", "")
                if (subtype.isNotEmpty()) {
                    return when (subtype) {
                        "mpeg", "mp3" -> "mp3"
                        "wav", "x-wav", "wave", "vnd.wave" -> "wav"
                        "ogg" -> "ogg"
                        "flac" -> "flac"
                        "webm" -> "webm"
                        "mp4" -> "mp4"
                        "x-m4a", "m4a" -> "m4a"
                        "mpga" -> "mpga"
                        else -> subtype
                    }
                }
            }

            // Fall back to filename extension
            if (filename != null) {
                val ext = filename.substringAfterLast(".", "").lowercase()
                if (ext.isNotEmpty()) return ext
            }

            return null
        }
    }
}
