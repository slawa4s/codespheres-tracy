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
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the OpenAI Audio Speech (text-to-speech) API.
 *
 * Handles JSON requests for:
 * - `/v1/audio/speech` (operationName = "audio.speech")
 *
 * Extracts model, voice, response_format, and speed from the request body.
 * Reads the response audio size from the reserved `_tracy.response.size_bytes` key
 * injected by the interceptor for audio/&#42; MIME types, and sets
 * `gen_ai.response.audio.size_bytes` on the span.
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
        body["speed"]?.jsonPrimitive?.content?.toDoubleOrNull()?.let {
            span.setAttribute(AttributeKey.doubleKey("gen_ai.request.audio.speed"), it)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute(AttributeKey.longKey("http.status_code"), response.code.toLong())

        val body = response.body.asJson()?.jsonObject ?: return
        body["_tracy.response.size_bytes"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute(AttributeKey.longKey("gen_ai.response.audio.size_bytes"), it)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.debug { "Audio speech API does not use server-sent events streaming" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
