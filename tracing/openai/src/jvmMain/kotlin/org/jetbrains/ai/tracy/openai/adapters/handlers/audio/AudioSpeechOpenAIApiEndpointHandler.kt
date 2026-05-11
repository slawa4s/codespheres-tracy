/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the OpenAI Audio Speech API (`POST /v1/audio/speech`).
 *
 * Parses a JSON request body and sets `gen_ai.operation.name = "audio.speech"`,
 * `openai.api.type = "audio"`, and `gen_ai.output.type = "speech"` unconditionally.
 * Extracts [GEN_AI_REQUEST_MODEL], `tracy.request.voice`, `tracy.request.response_format`,
 * and `tracy.request.speed` from the request body.
 *
 * The audio speech response is binary; if the interceptor injects `_tracy_binary_size_bytes`
 * into the response JSON envelope, `tracy.response.audio.size_bytes` is set accordingly.
 *
 * See [OpenAI Audio Speech API](https://platform.openai.com/docs/api-reference/audio/createSpeech)
 */
internal class AudioSpeechOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gen_ai.operation.name", "audio.speech")
        span.setAttribute("openai.api.type", "audio")
        span.setAttribute("gen_ai.output.type", "speech")

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
        val body = response.body.asJson()?.jsonObject ?: return

        body["_tracy_binary_size_bytes"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.response.audio.size_bytes", it)
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
