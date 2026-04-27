/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput

/**
 * Extracts request/response attributes for the OpenAI Audio Speech API.
 *
 * Request bodies are JSON with `model`, `input`, `voice`, and optional `response_format` fields.
 * Response bodies are binary audio data (e.g., mp3, opus) — size is sourced from the
 * `_tracy_binary_size` field injected by [OpenTelemetryOkHttpInterceptor] for non-JSON responses.
 *
 * See [Audio Speech API](https://platform.openai.com/docs/api-reference/audio/createSpeech)
 */
internal class SpeechOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gen_ai.operation.name", "audio.speech")
        span.setAttribute("gen_ai.output.type", "speech")

        val body = request.body.asJson()?.jsonObject ?: return

        body["input"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.input", it.orRedactedInput())
        }
        body["voice"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.voice", it)
        }
        body["response_format"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.response_format", it)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // The response body is binary audio; its byte count is injected as _tracy_binary_size
        // by OpenTelemetryOkHttpInterceptor when the content-type is not application/json.
        val body = response.body.asJson()?.jsonObject ?: return
        body["_tracy_binary_size"]?.jsonPrimitive?.longOrNull?.let { size ->
            span.setAttribute("gen_ai.response.audio.size_bytes", size)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio speech does not use SSE streaming
    }
}
