/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts request attributes for the OpenAI Audio Speech API.
 *
 * The response is binary audio data, so no response body attributes are extracted.
 *
 * See [Audio Speech API](https://platform.openai.com/docs/api-reference/audio/createSpeech)
 */
internal class SpeechOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "audio.speech")
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "speech")

        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.jsonPrimitive.content) }
        body["voice"]?.let { span.setAttribute("gen_ai.request.voice", it.jsonPrimitive.content) }
        body["response_format"]?.let { span.setAttribute("gen_ai.request.response_format", it.jsonPrimitive.content) }
            ?: span.setAttribute("gen_ai.request.response_format", "mp3")
        body["input"]?.let { span.setAttribute("gen_ai.request.input", it.jsonPrimitive.content.orRedactedInput()) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // The speech API returns binary audio data; no structured response body to extract.
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio speech responses are not streamed as SSE events; no-op.
    }
}
