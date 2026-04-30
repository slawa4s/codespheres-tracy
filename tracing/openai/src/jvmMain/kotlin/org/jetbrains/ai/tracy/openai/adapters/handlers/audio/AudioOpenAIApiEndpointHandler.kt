/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils

/**
 * Handler for the OpenAI Audio API.
 *
 * Covers the two transcription endpoints:
 * 1. `POST /v1/audio/transcriptions` - Transcribes audio into text (`audio.transcriptions`)
 * 2. `POST /v1/audio/translations` - Translates audio into English text (`audio.translations`)
 *
 * This handler sets `gen_ai.operation.name` and `openai.api.type` on each span.
 *
 * See [Audio API Reference](https://platform.openai.com/docs/api-reference/audio)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setNetworkRequestAttributes(span, request)
        val operationName = deriveOperationName(request.url)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
        span.setAttribute("openai.api.type", "audio")
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setHttpStatusCode(span, response)
        // Override gen_ai.operation.name with the URL-derived value.
        val operationName = deriveOperationName(response.url)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio API does not use server-sent events streaming
        logger.warn { "Audio API does not use server-sent events streaming" }
    }

    /**
     * Derives the `gen_ai.operation.name` from the request URL.
     *
     * - `POST /v1/audio/transcriptions` → `"audio.transcriptions"`
     * - `POST /v1/audio/translations`   → `"audio.translations"`
     */
    private fun deriveOperationName(url: TracyHttpUrl): String {
        val route = url.pathSegments.joinToString(separator = "/")
        return when {
            route.contains("audio/transcriptions") -> "audio.transcriptions"
            route.contains("audio/translations") -> "audio.translations"
            else -> {
                logger.warn { "Unknown audio route: $route. Defaulting to 'audio.transcriptions'." }
                "audio.transcriptions"
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
