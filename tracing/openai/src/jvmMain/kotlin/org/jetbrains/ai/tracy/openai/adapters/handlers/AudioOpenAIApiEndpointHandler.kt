/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.populateUnmappedAttributes
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Handler for OpenAI Audio API (speech, transcription, translation).
 * See: https://platform.openai.com/docs/api-reference/audio
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val path = request.url.pathSegments.joinToString("/")
        when {
            path.contains("audio/speech") -> handleSpeechRequest(span, request)
            path.contains("audio/transcriptions") -> handleTranscriptionRequest(span, request)
            path.contains("audio/translations") -> handleTranslationRequest(span, request)
        }
    }

    private fun handleSpeechRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        span.setAttribute("gen_ai.output.type", "speech")
        span.populateUnmappedAttributes(body, speechMappedRequestAttributes, PayloadType.REQUEST)
    }

    private fun handleTranscriptionRequest(span: Span, request: TracyHttpRequest) {
        handleAudioFormRequest(span, request, "audio.transcription")
    }

    private fun handleTranslationRequest(span: Span, request: TracyHttpRequest) {
        handleAudioFormRequest(span, request, "audio.translation")
    }

    private fun handleAudioFormRequest(span: Span, request: TracyHttpRequest, operationName: String) {
        val formData = request.body.asFormData() ?: return

        for (part in formData.parts) {
            val charset = part.contentType?.charset() ?: Charsets.UTF_8
            val contentType = part.contentType
            when (part.name) {
                "model" -> {
                    val model = part.content.toString(charset)
                    span.setAttribute(GEN_AI_REQUEST_MODEL, model)
                }
                "response_format" -> {
                    val fmt = part.content.toString(charset)
                    span.setAttribute("tracy.request.response_format", fmt)
                    val outputType = when (fmt) {
                        "verbose_json", "json" -> "json"
                        "srt", "vtt", "text" -> "text"
                        else -> fmt
                    }
                    span.setAttribute("gen_ai.output.type", outputType)
                }
                "include", "include[]" -> {
                    val value = part.content.toString(charset)
                    span.setAttribute("tracy.request.include", value)
                }
                "timestamp_granularities", "timestamp_granularities[]" -> {
                    val value = part.content.toString(charset)
                    span.setAttribute("tracy.request.timestamp_granularities", value)
                }
                "stream" -> {
                    val boolVal = part.content.toString(charset).lowercase() == "true"
                    span.setAttribute("gen_ai.request.stream", boolVal)
                }
                "file" -> {
                    span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                    val format = part.filename?.substringAfterLast(".")
                        ?: contentType?.subtype
                    if (format != null) {
                        span.setAttribute("tracy.request.audio.format", format)
                    }
                }
                "prompt" -> {
                    span.setAttribute("tracy.request.prompt.present", true)
                }
                "temperature" -> {
                    val value = part.content.toString(charset)
                    value.toDoubleOrNull()?.let {
                        span.setAttribute("tracy.request.temperature", it)
                    } ?: span.setAttribute("tracy.request.temperature", value)
                }
                else -> {
                    if (part.name != null) {
                        val value = part.content.toString(charset)
                        span.setAttribute("tracy.request.${part.name}", value)
                    }
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val path = response.url.pathSegments.joinToString("/")
        val body = response.body.asJson()?.jsonObject

        // Parse error details when present (e.g. 400/422 responses)
        (body?.get("error") as? JsonObject)?.let { error ->
            error["message"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.response.error.message", it)
            }
            error["type"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.response.error.type", it)
            }
            error["code"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.response.error.code", it)
            }
        }

        when {
            path.contains("audio/speech") -> {
                // response_content_length is injected by the interceptor for binary responses
                body?.get("response_content_length")?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.response.audio.size_bytes", it)
                }
            }
            path.contains("audio/transcriptions") || path.contains("audio/translations") -> {
                if (body != null) {
                    val responsePrefix = if (path.contains("audio/translations")) "translation" else "transcription"
                    body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
                        span.setAttribute("tracy.response.$responsePrefix.duration_seconds", it)
                    }
                    body["language"]?.jsonPrimitive?.content?.let {
                        span.setAttribute("tracy.response.$responsePrefix.language", it)
                    }
                    (body["words"] as? JsonArray)?.let {
                        span.setAttribute("tracy.response.$responsePrefix.words.count", it.size.toLong())
                    }
                    span.populateUnmappedAttributes(body, listOf("duration", "language", "words"), PayloadType.RESPONSE)
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        val eventCount = events.lineSequence().count { it.startsWith("data:") }
        if (eventCount > 0) {
            span.setAttribute("tracy.response.stream.events.count", eventCount.toLong())
        }
    }

    private val speechMappedRequestAttributes = listOf("model")
}
