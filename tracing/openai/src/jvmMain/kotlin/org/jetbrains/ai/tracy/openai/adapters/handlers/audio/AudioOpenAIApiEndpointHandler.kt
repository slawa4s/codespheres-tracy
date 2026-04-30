/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Audio API endpoints.
 *
 * Supports:
 * - `POST /v1/audio/transcriptions` – transcribe audio to text (`audio.transcriptions.create`)
 * - `POST /v1/audio/translations`   – translate audio to English text (`audio.translations.create`)
 *
 * Both endpoints accept `multipart/form-data` with a `model` field and return
 * `{"text": "..."}` (default JSON format), so the existing chat-completions
 * response parsing is not applicable here.
 *
 * See [Audio API Reference](https://platform.openai.com/docs/api-reference/audio)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setNetworkRequestAttributes(span, request)
        val operationName = deriveOperationName(request.url)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
        span.setAttribute("openai.api.type", "audio")

        val formData = request.body.asFormData() ?: return
        for (part in formData.parts) {
            if (part.name == "model") {
                val charset = part.contentType?.charset() ?: Charsets.UTF_8
                span.setAttribute(GEN_AI_REQUEST_MODEL, part.content.toString(charset))
                break
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setHttpStatusCode(span, response)

        val body = response.body.asJson()?.jsonObject ?: return
        body["text"]?.let {
            span.setAttribute("gen_ai.response.text", it.jsonPrimitive.content.orRedactedOutput())
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio transcription and translation endpoints do not use server-sent events streaming
        logger.warn { "Audio API does not use server-sent events streaming" }
    }

    /**
     * Derives the `gen_ai.operation.name` from the request URL path.
     *
     * - `POST /v1/audio/transcriptions` → `"audio.transcriptions.create"`
     * - `POST /v1/audio/translations`   → `"audio.translations.create"`
     */
    private fun deriveOperationName(url: TracyHttpUrl): String {
        val segments = url.pathSegments
        val audioIndex = segments.indexOf("audio")
        if (audioIndex == -1) {
            logger.warn { "Failed to detect audio route. Endpoint has no `audio` path segment: ${segments.joinToString(separator = "/")}" }
            return "audio.transcriptions.create"
        }

        val subRoute = if (segments.size > audioIndex + 1) segments[audioIndex + 1] else ""
        return when (subRoute) {
            "transcriptions" -> "audio.transcriptions.create"
            "translations" -> "audio.translations.create"
            else -> {
                logger.warn { "Unknown audio sub-route '$subRoute'. Defaulting to audio.transcriptions.create" }
                "audio.transcriptions.create"
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
