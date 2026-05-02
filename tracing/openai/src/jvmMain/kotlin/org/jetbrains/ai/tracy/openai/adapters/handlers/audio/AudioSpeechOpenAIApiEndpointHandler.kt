/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Audio Speech API endpoint:
 * - POST /v1/audio/speech
 *
 * Parses JSON requests to extract the model, voice, response format, and speed.
 * The response is a binary audio stream and contains no JSON to parse.
 *
 * The [GEN_AI_OPERATION_NAME] is set to "audio.speech" during request handling so
 * that it is captured even when the API returns an error response.
 *
 * See [Audio Speech API](https://platform.openai.com/docs/api-reference/audio/createSpeech)
 */
internal class AudioSpeechOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "audio.speech")

        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it)
        }
        body["voice"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.voice", it)
        }
        body["response_format"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.response_format", it)
        }
        body["speed"]?.jsonPrimitive?.double?.let {
            span.setAttribute("gen_ai.request.speed", it)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // The audio/speech response is binary audio data — no JSON attributes to extract.
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio speech endpoint does not support server-sent events streaming.
        logger.warn { "Audio speech API does not use server-sent events streaming" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
