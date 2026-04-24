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
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts request/response attributes for the Audio Speech (text-to-speech) API.
 *
 * See [Audio Speech API](https://platform.openai.com/docs/api-reference/audio/createSpeech)
 */
internal class AudioSpeechOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }
        body["input"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.request.input", it.orRedactedInput()) }
        body["voice"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.request.voice", it) }
        body["response_format"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.request.response_format", it) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute("gen_ai.output.type", "speech")

        val contentLength = response.headers["content-length"]?.toLongOrNull()
        if (contentLength != null && contentLength >= 0) {
            span.setAttribute("gen_ai.response.audio.size_bytes", contentLength)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // audio speech does not use SSE streaming
    }
}
