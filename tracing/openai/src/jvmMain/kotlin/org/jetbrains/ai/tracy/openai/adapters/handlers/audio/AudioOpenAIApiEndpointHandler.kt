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
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils

/**
 * Extracts request/response attributes for the OpenAI Audio API.
 *
 * Handles three endpoints:
 * - `POST /v1/audio/transcriptions` → operation name `audio.transcription`
 * - `POST /v1/audio/translations`   → operation name `audio.translation`
 * - `POST /v1/audio/speech`         → operation name `audio.speech`
 *
 * Transcription/translation endpoints accept `multipart/form-data` with the following parts:
 * - `file`                    → audio file binary; maps to `tracy.request.audio.size_bytes` and `tracy.request.audio.format`
 * - `model`                   → model name; maps to `gen_ai.request.model`
 * - `response_format`         → desired output format; maps to `tracy.request.response_format`
 * - `timestamp_granularities` → one part per granularity value; joined with commas as `tracy.request.timestamp_granularities`
 *
 * When `response_format` is `json` or `verbose_json`, `gen_ai.output.type` is set to `json`.
 *
 * The transcription/translation response body is `{"text": "..."}` (not chat choices), so response parsing
 * extracts `gen_ai.response.text` directly from the top-level `text` field.
 * For verbose_json responses, additional fields are extracted:
 * - `duration` → `tracy.response.transcription.duration_seconds`
 * - `language` → `tracy.response.transcription.language`
 * - `words`    → `tracy.response.transcription.words.count` (array size)
 *
 * The speech endpoint accepts `application/json` with the following fields:
 * - `model`           → model name; maps to `gen_ai.request.model`
 * - `input`           → text to synthesize; maps to `gen_ai.request.input`
 * - `voice`           → voice name; maps to `tracy.request.voice`
 * - `response_format` → desired audio format; maps to `tracy.request.response_format`
 * - `speed`           → playback speed multiplier; maps to `tracy.request.speed`
 *
 * The speech response is binary audio; `tracy.response.audio.size_bytes` is read from the Content-Length header.
 *
 * See [Audio API Reference](https://platform.openai.com/docs/api-reference/audio)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setNetworkRequestAttributes(span, request)

        val operationName = deriveOperationName(request.url.pathSegments)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
        span.setAttribute("openai.api.type", "audio")

        val formData = request.body.asFormData()
        if (formData != null) {
            var responseFormat: String? = null
            val timestampGranularities = mutableListOf<String>()

            for (part in formData.parts) {
                val charset = part.contentType?.charset() ?: Charsets.UTF_8
                when (part.name) {
                    "model" -> {
                        span.setAttribute(GEN_AI_REQUEST_MODEL, part.content.toString(charset))
                    }
                    "response_format" -> {
                        val value = part.content.toString(charset)
                        responseFormat = value
                        span.setAttribute("tracy.request.response_format", value)
                    }
                    "file" -> {
                        span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                        part.filename?.substringAfterLast('.')?.let { ext ->
                            span.setAttribute("tracy.request.audio.format", ext)
                        }
                    }
                    "timestamp_granularities" -> {
                        timestampGranularities.add(part.content.toString(charset))
                    }
                }
            }

            if (timestampGranularities.isNotEmpty()) {
                span.setAttribute("tracy.request.timestamp_granularities", timestampGranularities.joinToString(","))
            }

            if ((operationName == "audio.transcription" || operationName == "audio.translation") &&
                (responseFormat == "json" || responseFormat == "verbose_json")
            ) {
                span.setAttribute("gen_ai.output.type", "json")
            }
        }

        if (operationName == "audio.speech") {
            span.setAttribute("gen_ai.output.type", "speech")
            val jsonBody = request.body.asJson()?.jsonObject ?: return
            jsonBody["model"]?.jsonPrimitive?.content?.let {
                span.setAttribute(GEN_AI_REQUEST_MODEL, it)
            }
            jsonBody["input"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.request.input", it.orRedactedInput())
            }
            jsonBody["voice"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.voice", it)
            }
            jsonBody["response_format"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.response_format", it)
            }
            jsonBody["speed"]?.jsonPrimitive?.doubleOrNull?.let {
                span.setAttribute("tracy.request.speed", it)
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setHttpStatusCode(span, response)

        if (deriveOperationName(response.url.pathSegments) == "audio.speech") {
            response.contentLength?.let {
                span.setAttribute("tracy.response.audio.size_bytes", it)
            }
            return
        }

        val body = response.body.asJson()?.jsonObject ?: return
        body["text"]?.let {
            span.setAttribute("gen_ai.response.text", it.jsonPrimitive.content.orRedactedOutput())
        }
        body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute("tracy.response.transcription.duration_seconds", it)
        }
        body["language"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.transcription.language", it)
        }
        (body["words"] as? JsonArray)?.size?.toLong()?.let {
            span.setAttribute("tracy.response.transcription.words.count", it)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio API does not use server-sent events streaming
        logger.warn { "Audio API does not use server-sent events streaming" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Derives the GenAI operation name from the URL path segments.
         *
         * - path contains "transcriptions" → `"audio.transcription"`
         * - path contains "translations"   → `"audio.translation"`
         * - path contains "speech"         → `"audio.speech"`
         * - otherwise falls back to        → `"audio"`
         */
        internal fun deriveOperationName(pathSegments: List<String>): String {
            return when {
                pathSegments.any { it == "transcriptions" } -> "audio.transcription"
                pathSegments.any { it == "translations" } -> "audio.translation"
                pathSegments.any { it == "speech" } -> "audio.speech"
                else -> {
                    logger.warn { "Unknown audio endpoint. Path: ${pathSegments.joinToString("/")} Defaulting to 'audio'." }
                    "audio"
                }
            }
        }
    }
}
