/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_TEMPERATURE
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput

/**
 * Handler for OpenAI Audio API endpoints:
 * - `POST /audio/transcriptions` — Speech to text
 * - `POST /audio/translations`   — Speech to English text
 * - `POST /audio/speech`         — Text to speech
 *
 * Audio transcription/translation responses are `{"text": "..."}` with no `"object"` field,
 * so [org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils.setCommonResponseAttributes]
 * cannot infer `gen_ai.operation.name` from the response body.  The operation name is therefore
 * set explicitly in [handleRequestAttributes] so that it is present even when the API returns an
 * error response (where no response body is available).
 *
 * See [OpenAI Audio API Reference](https://platform.openai.com/docs/api-reference/audio)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url)
        // Set operation name during request handling so it is captured even on error paths.
        // Audio responses have no "object" field, so the setCommonResponseAttributes fallback
        // would leave gen_ai.operation.name absent whenever the API returns an error.
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        when (route) {
            AudioRoute.TRANSCRIPTIONS, AudioRoute.TRANSLATIONS -> handleAudioInputRequest(span, request)
            AudioRoute.SPEECH -> handleSpeechRequest(span, request)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url)
        when (route) {
            AudioRoute.TRANSCRIPTIONS, AudioRoute.TRANSLATIONS -> {
                val body = response.body.asJson()?.jsonObject ?: return
                body["text"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.completion.0.content", it.orRedactedOutput())
                }
            }
            AudioRoute.SPEECH -> {
                // Response is binary audio; no JSON attributes to extract.
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio API does not use server-sent events streaming.
        logger.warn { "Audio API does not use server-sent events streaming" }
    }

    /**
     * Extracts request attributes for transcription and translation endpoints.
     *
     * Both accept `multipart/form-data` with: `file` (binary, skipped), `model`,
     * `language` (transcriptions only), `prompt`, `response_format`, and `temperature`.
     */
    private fun handleAudioInputRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asFormData() ?: return
        for (part in body.parts) {
            val contentType = part.contentType
            // Skip binary parts such as the audio file itself.
            if (contentType != null && contentType.type != "text") continue
            val content = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
            when (part.name) {
                "model" -> span.setAttribute(GEN_AI_REQUEST_MODEL, content)
                "language" -> span.setAttribute("gen_ai.request.language", content)
                "prompt" -> span.setAttribute("gen_ai.prompt.0.content", content.orRedactedInput())
                "response_format" -> span.setAttribute("gen_ai.request.response_format", content)
                "temperature" -> content.toDoubleOrNull()?.let {
                    span.setAttribute(GEN_AI_REQUEST_TEMPERATURE, it)
                }
            }
        }
    }

    /**
     * Extracts request attributes for the speech (TTS) endpoint.
     *
     * Accepts JSON with: `model`, `input` (text to synthesise), `voice`,
     * `response_format`, and `speed`.
     */
    private fun handleSpeechRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        body["model"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }
        body["input"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.prompt.0.content", it.orRedactedInput())
        }
        body["voice"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.request.voice", it) }
        body["response_format"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.response_format", it)
        }
        body["speed"]?.jsonPrimitive?.content?.toDoubleOrNull()?.let {
            span.setAttribute("gen_ai.request.speed", it)
        }
    }

    private fun detectRoute(url: TracyHttpUrl): AudioRoute {
        val segments = url.pathSegments
        return when {
            segments.contains("transcriptions") -> AudioRoute.TRANSCRIPTIONS
            segments.contains("translations") -> AudioRoute.TRANSLATIONS
            segments.contains("speech") -> AudioRoute.SPEECH
            else -> {
                logger.warn { "Failed to detect audio route: ${url.pathSegments.joinToString(separator = "/")}" }
                AudioRoute.TRANSCRIPTIONS
            }
        }
    }

    private enum class AudioRoute(val operationName: String) {
        TRANSCRIPTIONS("audio.transcriptions"),
        TRANSLATIONS("audio.translations"),
        SPEECH("audio.speech"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
