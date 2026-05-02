/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponseBody
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput

/**
 * Handler for the OpenAI Audio Speech endpoint:
 * - POST /v1/audio/speech
 *
 * Parses the JSON request body to extract the model, voice, response format, and input text.
 * The TTS endpoint returns raw binary audio, so [handleResponseAttributes] reads the byte length
 * from the [TracyHttpResponseBody.Binary] body rather than attempting JSON parsing.
 *
 * The [GEN_AI_OPERATION_NAME] is set to `"audio.speech"` during request handling so that it is
 * captured even when the API returns an error response.
 *
 * See [Audio Speech API](https://platform.openai.com/docs/api-reference/audio/createSpeech)
 */
internal class AudioSpeechOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "audio.speech")
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "speech")

        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }
        body["voice"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.request.voice", it) }
        body["response_format"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.request.response_format", it) }
        body["input"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.request.input", it.orRedactedInput()) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // TTS returns binary audio — read size from the Binary body, do not attempt JSON parsing.
        val sizeBytes = when (val body = response.body) {
            is TracyHttpResponseBody.Binary -> body.bytes.size.toLong()
            is TracyHttpResponseBody.Json -> null
        }
        sizeBytes?.let { span.setAttribute("gen_ai.response.audio.size_bytes", it) }
    }

    override fun handleStreaming(span: Span, events: String) {
        // The audio speech endpoint does not support server-sent events streaming.
    }
}
