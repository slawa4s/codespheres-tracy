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
 * - `file`            → audio file binary; maps to `gen_ai.request.audio.size_bytes` and `gen_ai.request.audio.format`
 * - `model`           → model name; maps to `gen_ai.request.model`
 * - `response_format` → desired output format; maps to `gen_ai.request.response_format`
 *
 * The transcription/translation response body is `{"text": "..."}` (not chat choices), so response parsing
 * extracts `gen_ai.response.text` directly from the top-level `text` field.
 *
 * The speech endpoint accepts `application/json` with the following fields:
 * - `input`           → text to synthesize; maps to `gen_ai.request.input`
 * - `voice`           → voice name; maps to `gen_ai.request.voice`
 * - `response_format` → desired audio format; maps to `gen_ai.request.response_format`
 *
 * The speech response is binary audio; `gen_ai.response.audio.size_bytes` is read from the Content-Length header.
 *
 * See [Audio API Reference](https://platform.openai.com/docs/api-reference/audio)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setNetworkRequestAttributes(span, request)

        val operationName = deriveOperationName(request.url.pathSegments)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)

        val formData = request.body.asFormData()
        if (formData != null) {
            for (part in formData.parts) {
                val charset = part.contentType?.charset() ?: Charsets.UTF_8
                when (part.name) {
                    "model" -> {
                        span.setAttribute(GEN_AI_REQUEST_MODEL, part.content.toString(charset))
                    }
                    "response_format" -> {
                        span.setAttribute("gen_ai.request.response_format", part.content.toString(charset))
                    }
                    "file" -> {
                        span.setAttribute("gen_ai.request.audio.size_bytes", part.content.size.toLong())
                        part.filename?.substringAfterLast('.')?.let { ext ->
                            span.setAttribute("gen_ai.request.audio.format", ext)
                        }
                    }
                }
            }
        }

        if (operationName == "audio.speech") {
            span.setAttribute("gen_ai.output.type", "speech")
            val jsonBody = request.body.asJson()?.jsonObject ?: return
            jsonBody["input"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.request.input", it.orRedactedInput())
            }
            jsonBody["voice"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.request.voice", it)
            }
            jsonBody["response_format"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.request.response_format", it)
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setHttpStatusCode(span, response)

        if (deriveOperationName(response.url.pathSegments) == "audio.speech") {
            response.contentLength?.let {
                span.setAttribute("gen_ai.response.audio.size_bytes", it)
            }
            return
        }

        val body = response.body.asJson()?.jsonObject ?: return
        body["text"]?.let {
            span.setAttribute("gen_ai.response.text", it.jsonPrimitive.content.orRedactedOutput())
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
