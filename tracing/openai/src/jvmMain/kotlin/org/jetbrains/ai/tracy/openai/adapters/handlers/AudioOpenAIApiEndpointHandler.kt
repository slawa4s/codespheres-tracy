/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Audio API (speech, transcriptions, translations).
 *
 * See [Audio API](https://platform.openai.com/docs/api-reference/audio)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "audio")

        when (detectAudioRoute(request.url)) {
            AudioRoute.SPEECH -> handleSpeechRequest(span, request)
            AudioRoute.TRANSCRIPTION -> handleTranscriptionRequest(span, request)
            AudioRoute.TRANSLATION -> handleTranslationRequest(span, request)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        when (detectAudioRoute(response.url)) {
            AudioRoute.SPEECH -> handleSpeechResponse(span, response)
            AudioRoute.TRANSCRIPTION -> handleTranscriptionResponse(span, response)
            AudioRoute.TRANSLATION -> handleTranslationResponse(span, response)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        var eventCount = 0
        for (line in events.lineSequence()) {
            if (line.startsWith("data:")) {
                eventCount++
            }
        }
        if (eventCount > 0) {
            span.setAttribute("tracy.response.stream.events.count", eventCount.toLong())
        }
    }

    private fun handleSpeechRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "audio.speech")
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "speech")

        val body = request.body.asJson()?.jsonObject ?: return
        body["model"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }
        body["voice"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.voice", it) }
        body["response_format"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.response_format", it) }
        body["speed"]?.jsonPrimitive?.doubleOrNull?.let { span.setAttribute("tracy.request.speed", it) }
        body["stream_format"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.stream_format", it) }
    }

    private fun handleSpeechResponse(span: Span, response: TracyHttpResponse) {
        // Response is binary audio — capture size from Content-Length header
        response.contentLength?.let { span.setAttribute("tracy.response.audio.size_bytes", it) }
    }

    private fun handleTranscriptionRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "audio.transcription")

        val formData = request.body.asFormData()
        if (formData != null) {
            for (part in formData.parts) {
                val charset = part.contentType?.charset() ?: Charsets.UTF_8
                when (part.name) {
                    "model" -> span.setAttribute(GEN_AI_REQUEST_MODEL, part.content.toString(charset))
                    "response_format" -> {
                        val fmt = part.content.toString(charset)
                        span.setAttribute("tracy.request.response_format", fmt)
                        // json and verbose_json map to "json" output type
                        if (fmt.contains("json", ignoreCase = true)) {
                            span.setAttribute(GEN_AI_OUTPUT_TYPE, "json")
                        }
                    }
                    "language" -> span.setAttribute("tracy.request.language", part.content.toString(charset))
                    "temperature" -> part.content.toString(charset).toDoubleOrNull()?.let {
                        span.setAttribute("tracy.request.temperature", it)
                    }
                    "timestamp_granularities[]" -> span.setAttribute("tracy.request.timestamp_granularities", part.content.toString(charset))
                    "include[]", "include" -> span.setAttribute("tracy.request.include", part.content.toString(charset))
                    "stream" -> part.content.toString(charset).toBooleanStrictOrNull()?.let {
                        span.setAttribute("gen_ai.request.stream", it)
                    }
                    "file" -> captureAudioFilePart(span, part)
                    else -> {
                        // Audio file part with non-standard name — record size and format if audio
                        val ct = part.contentType
                        if (ct != null && ct.type == "audio") {
                            span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                            span.setAttribute("tracy.request.audio.format", ct.subtype)
                        }
                    }
                }
            }
        }
    }

    private fun handleTranscriptionResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute("tracy.response.transcription.duration_seconds", it)
        }
        body["language"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.transcription.language", it)
        }
        val words = body["words"]?.jsonArray
        if (words != null) {
            span.setAttribute("tracy.response.transcription.words.count", words.size.toLong())
        }
    }

    private fun handleTranslationRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "audio.translation")

        val formData = request.body.asFormData()
        if (formData != null) {
            for (part in formData.parts) {
                val charset = part.contentType?.charset() ?: Charsets.UTF_8
                when (part.name) {
                    "model" -> span.setAttribute(GEN_AI_REQUEST_MODEL, part.content.toString(charset))
                    "response_format" -> {
                        val fmt = part.content.toString(charset)
                        span.setAttribute("tracy.request.response_format", fmt)
                        if (fmt.contains("json", ignoreCase = true)) {
                            span.setAttribute(GEN_AI_OUTPUT_TYPE, "json")
                        }
                    }
                    "temperature" -> part.content.toString(charset).toDoubleOrNull()?.let {
                        span.setAttribute("tracy.request.temperature", it)
                    }
                    "prompt" -> {
                        // Don't log prompt content, just note it's present
                        span.setAttribute("tracy.request.prompt.present", true)
                    }
                    "file" -> captureAudioFilePart(span, part)
                    else -> {
                        val ct = part.contentType
                        if (ct != null && ct.type == "audio") {
                            span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                            span.setAttribute("tracy.request.audio.format", ct.subtype)
                        }
                    }
                }
            }
        }
    }

    private fun handleTranslationResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute("tracy.response.translation.duration_seconds", it)
        }
    }

    private fun captureAudioFilePart(span: Span, part: org.jetbrains.ai.tracy.core.http.parsers.FormPart) {
        span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
        val ct = part.contentType
        val format = when {
            ct?.type == "audio" -> ct.subtype
            part.filename != null -> part.filename!!.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
            else -> null
        }
        format?.let { span.setAttribute("tracy.request.audio.format", it) }
    }

    private enum class AudioRoute { SPEECH, TRANSCRIPTION, TRANSLATION }

    private fun detectAudioRoute(url: TracyHttpUrl): AudioRoute {
        val path = url.pathSegments.joinToString("/")
        return when {
            path.contains("speech") -> AudioRoute.SPEECH
            path.contains("translation") -> AudioRoute.TRANSLATION
            else -> AudioRoute.TRANSCRIPTION
        }
    }
}
