/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging

/**
 * Extracts request/response attributes for the OpenAI Audio Speech API.
 *
 * See [Audio Speech API](https://platform.openai.com/docs/api-reference/audio/createSpeech)
 */
internal class AudioSpeechOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "audio")

        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it)
        }
        body["voice"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.voice", it)
        }
        body["response_format"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.response_format", it)
        }
        body["speed"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute("tracy.request.speed", it)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // The response is binary audio data. The interceptor injects the content-length as
        // "_tracy_response_size_bytes" in the synthetic JSON object passed for non-JSON responses.
        val body = response.body.asJson()?.jsonObject ?: return

        body["_tracy_response_size_bytes"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.response.audio.size_bytes", it)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio speech endpoint does not use SSE streaming
    }

    companion object {
        private const val OPERATION_NAME = "audio.speech"
        private val logger = KotlinLogging.logger {}
    }
}
