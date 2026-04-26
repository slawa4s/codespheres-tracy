/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput

/**
 * Extracts request/response bodies of the OpenAI Audio Transcriptions and Audio Translations APIs.
 *
 * Request bodies are `multipart/form-data` with at minimum a `file` and `model` part.
 * Response bodies are JSON with a `text` field containing the transcription/translation.
 *
 * See [Audio Transcriptions API](https://platform.openai.com/docs/api-reference/audio/createTranscription)
 * See [Audio Translations API](https://platform.openai.com/docs/api-reference/audio/createTranslation)
 */
internal class AudioOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        // Determine operation name from URL path
        val pathSegments = request.url.pathSegments
        val operationName = when {
            pathSegments.any { it == "transcriptions" } -> "audio.transcription"
            pathSegments.any { it == "translations" } -> "audio.translation"
            else -> "audio.transcription"
        }
        span.setAttribute("gen_ai.operation.name", operationName)

        val body = request.body.asFormData() ?: return

        for (part in body.parts) {
            when (part.name) {
                "model" -> {
                    val content = part.content.toString(
                        part.contentType?.charset() ?: Charsets.UTF_8
                    )
                    span.setAttribute(GEN_AI_REQUEST_MODEL, content)
                }

                "response_format" -> {
                    val content = part.content.toString(
                        part.contentType?.charset() ?: Charsets.UTF_8
                    )
                    span.setAttribute("gen_ai.request.response_format", content)
                }

                "file" -> {
                    // Size in bytes
                    span.setAttribute("gen_ai.request.audio.size_bytes", part.content.size.toLong())

                    // Format from Content-Type subtype (e.g. "wav", "mp3") or filename extension
                    val format = part.contentType?.subtype?.takeIf { it.isNotBlank() }
                        ?: part.filename?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
                    if (format != null) {
                        span.setAttribute("gen_ai.request.audio.format", format)
                    } else {
                        logger.warn { "Could not determine audio format for file part (no Content-Type subtype or filename extension)" }
                    }
                }

                null -> logger.warn { "Form data part with missing name ignored. Content type: '${part.contentType}'" }
                else -> {
                    // other text parts (language, prompt, temperature, etc.)
                    val contentType = part.contentType
                    if (contentType == null || contentType.type == "text") {
                        val content = part.content.toString(
                            contentType?.charset() ?: Charsets.UTF_8
                        )
                        span.setAttribute("gen_ai.request.${part.name}", content)
                    }
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["text"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.text", it.orRedactedOutput())
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio API does not use server-sent events streaming
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
