/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Audio Speech (TTS) API.
 *
 * Handles JSON requests for:
 * - `/v1/audio/speech`
 *
 * Extracts input text, voice, response_format, and model from the JSON request body,
 * and records the audio byte size from the response.
 *
 * The response body size is embedded into the JSON passed to [registerResponse] by the
 * interceptor (under key `gen_ai.response.audio.size_bytes`) for audio MIME types,
 * using the `Content-Length` header when available, falling back to peeking the body bytes.
 *
 * See [Audio Speech API Reference](https://platform.openai.com/docs/api-reference/audio/createSpeech)
 */
internal class AudioSpeechOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "audio.speech")
        span.setAttribute("gen_ai.output.type", "speech")

        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it)
        }
        body["input"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.input", it)
        }
        body["voice"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.voice", it)
        }
        body["response_format"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.response_format", it)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute(AttributeKey.longKey("http.status_code"), response.code.toLong())

        val body = response.body.asJson()?.jsonObject ?: return
        body["gen_ai.response.audio.size_bytes"]?.jsonPrimitive?.longOrNull?.let { sizeBytes ->
            span.setAttribute(AttributeKey.longKey("gen_ai.response.audio.size_bytes"), sizeBytes)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio speech API does not use server-sent events streaming
    }
}
