/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
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
 * Handles OpenAI Audio Speech API (`POST /v1/audio/speech`).
 *
 * Extracts span attributes from JSON request bodies.
 * Sets `openai.api.type = "audio"` and `gen_ai.operation.name = "audio.speech"` on every span.
 *
 * Request attributes extracted:
 * - `gen_ai.request.model` — model name (e.g. `tts-1`, `tts-1-hd`)
 * - `tracy.request.voice` — voice used for speech synthesis (e.g. `alloy`, `echo`, `nova`)
 * - `tracy.request.response_format` — audio format (e.g. `mp3`, `opus`, `aac`, `flac`, `wav`, `pcm`)
 * - `tracy.request.speed` — playback speed multiplier (0.25–4.0)
 *
 * Response attributes extracted:
 * - `tracy.response.audio.size_bytes` — byte size of the audio response body
 *
 * See [OpenAI Audio Speech](https://platform.openai.com/docs/api-reference/audio/createSpeech)
 */
internal class AudioSpeechOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "audio")
        span.setAttribute(GEN_AI_OPERATION_NAME, "audio.speech")
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "speech")

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
        body["tracy.response.body.size_bytes"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.response.audio.size_bytes", it)
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
