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
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.handlers.sse.sseHandlingUnsupported
import org.jetbrains.ai.tracy.core.http.parsers.SseEvent
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput

/**
 * Extracts request/response attributes for the OpenAI Audio Speech (TTS) API.
 *
 * See [Create speech API](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create)
 */
internal class AudioSpeechOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "speech")

        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it)
        }
        body["input"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.input", it.orRedactedInput())
        }
        body["voice"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.voice", it)
        }
        body["instructions"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.instructions", it.orRedactedInput())
        }
        body["response_format"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.response_format", it)
        }
        body["speed"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute("tracy.request.speed", it)
        }
        body["stream_format"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.stream_format", it)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // The response is an audio file (or a stream of audio events) — see
        // https://platform.openai.com/docs/api-reference/audio/createSpeech
        // TODO: trace MIME type and size in bytes once non-JSON response bodies
        //       are first-class in Tracy's TracyHttpResponse.
    }

    override fun handleStreamingEvent(
        span: Span,
        event: SseEvent,
        index: Long
    ): Result<Unit> {
        return sseHandlingUnsupported()
    }

    companion object {
        private const val OPERATION_NAME = "audio.speech"
    }
}
