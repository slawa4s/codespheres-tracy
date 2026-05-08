/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_TEMPERATURE
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Audio API.
 *
 * Handles transcriptions, translations, and speech endpoints.
 *
 * See: [Audio API](https://platform.openai.com/docs/api-reference/audio)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "audio")
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }

        when {
            segments.contains("transcriptions") -> {
                span.setAttribute(GEN_AI_OPERATION_NAME, "audio.transcription")
                handleTranscriptionTranslationRequest(span, request, isTranscription = true)
            }
            segments.contains("translations") -> {
                span.setAttribute(GEN_AI_OPERATION_NAME, "audio.translation")
                handleTranscriptionTranslationRequest(span, request, isTranscription = false)
            }
            segments.contains("speech") -> {
                span.setAttribute(GEN_AI_OPERATION_NAME, "audio.speech")
                span.setAttribute("gen_ai.output.type", "speech")
                handleSpeechRequest(span, request)
            }
        }
    }

    private fun handleTranscriptionTranslationRequest(span: Span, request: TracyHttpRequest, isTranscription: Boolean) {
        val form = request.body.asFormData() ?: return

        form.parts.firstOrNull { it.name == "model" }?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it.content.toString(Charsets.UTF_8))
        }

        val responseFormat = form.parts.firstOrNull { it.name == "response_format" }
            ?.content?.toString(Charsets.UTF_8)
        responseFormat?.let { span.setAttribute("tracy.request.response_format", it) }

        // Map response_format to gen_ai.output.type
        when (responseFormat) {
            "json", "verbose_json" -> span.setAttribute("gen_ai.output.type", "json")
            "text", "srt", "vtt" -> span.setAttribute("gen_ai.output.type", "text")
        }

        // Audio file metadata
        form.parts.firstOrNull { it.name == "file" }?.let { filePart ->
            span.setAttribute("tracy.request.audio.size_bytes", filePart.content.size.toLong())
            val format = filePart.filename?.substringAfterLast('.', "")?.lowercase()?.ifEmpty { null }
                ?: filePart.contentType?.subtype?.lowercase()
            format?.let { span.setAttribute("tracy.request.audio.format", it) }
        }

        form.parts.firstOrNull { it.name == "temperature" }?.let {
            it.content.toString(Charsets.UTF_8).toDoubleOrNull()?.let { temp ->
                span.setAttribute(GEN_AI_REQUEST_TEMPERATURE, temp)
                span.setAttribute("tracy.request.temperature", temp)
            }
        }

        if (isTranscription) {
            form.parts.firstOrNull { it.name == "language" }?.let {
                span.setAttribute("tracy.request.language", it.content.toString(Charsets.UTF_8))
            }

            // timestamp_granularities[] may appear multiple times
            val granularities = form.parts
                .filter { it.name == "timestamp_granularities[]" || it.name == "timestamp_granularities" }
                .map { it.content.toString(Charsets.UTF_8) }
            if (granularities.isNotEmpty()) {
                span.setAttribute("tracy.request.timestamp_granularities", granularities.joinToString(","))
            }

            // include[] may appear multiple times
            val includes = form.parts
                .filter { it.name == "include[]" || it.name == "include" }
                .map { it.content.toString(Charsets.UTF_8) }
            if (includes.isNotEmpty()) {
                span.setAttribute("tracy.request.include", includes.joinToString(","))
            }
        } else {
            // translation: prompt presence
            val prompt = form.parts.firstOrNull { it.name == "prompt" }
                ?.content?.toString(Charsets.UTF_8)
            if (!prompt.isNullOrEmpty()) {
                span.setAttribute("tracy.request.prompt.present", true)
            }
        }

        // streaming flag in form data
        form.parts.firstOrNull { it.name == "stream" }?.let {
            val value = it.content.toString(Charsets.UTF_8)
            if (value.toBooleanStrictOrNull() == true) {
                span.setAttribute("gen_ai.request.stream", true)
            }
        }
    }

    private fun handleSpeechRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        body["voice"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.request.voice", it) }
        body["response_format"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.request.response_format", it) }
        body["speed"]?.jsonPrimitive?.doubleOrNull?.let { span.setAttribute("tracy.request.speed", it) }
        body["stream_format"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.request.stream_format", it) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setCommonResponseAttributes(span, response)
        span.setAttribute("openai.api.type", "audio")

        val segments = response.url.pathSegments.filter { it.isNotEmpty() }
        when {
            segments.contains("transcriptions") -> {
                span.setAttribute(GEN_AI_OPERATION_NAME, "audio.transcription")
                val body = response.body.asJson()?.jsonObject ?: return
                // verbose_json response fields
                body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
                    span.setAttribute("tracy.response.transcription.duration_seconds", it)
                }
                body["language"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.response.transcription.language", it)
                }
                body["words"]?.let { words ->
                    try {
                        span.setAttribute("tracy.response.transcription.words.count", words.jsonArray.size.toLong())
                    } catch (_: Exception) { }
                }
            }
            segments.contains("translations") -> {
                span.setAttribute(GEN_AI_OPERATION_NAME, "audio.translation")
                val body = response.body.asJson()?.jsonObject ?: return
                body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
                    span.setAttribute("tracy.response.translation.duration_seconds", it)
                }
            }
            segments.contains("speech") -> {
                span.setAttribute(GEN_AI_OPERATION_NAME, "audio.speech")
                response.contentLength?.let { span.setAttribute("tracy.response.audio.size_bytes", it) }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        val eventCount = events.lineSequence().count { it.startsWith("data:") }
        if (eventCount > 0) {
            span.setAttribute("tracy.response.stream.events.count", eventCount.toLong())
        }
    }
}
