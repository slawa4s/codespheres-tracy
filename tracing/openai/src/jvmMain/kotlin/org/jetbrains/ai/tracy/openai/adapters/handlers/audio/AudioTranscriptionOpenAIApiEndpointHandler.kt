/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Extracts request/response bodies of the Audio Transcription API.
 *
 * See [Audio Transcription API](https://platform.openai.com/docs/api-reference/audio/createTranscription)
 */
internal class AudioTranscriptionOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "audio.transcription")
        span.setAttribute("openai.api.type", "audio")

        val body = request.body.asFormData() ?: return

        val timestampGranularities = mutableListOf<String>()

        for (part in body.parts) {
            // TODO: TRACY-88
            val contentType = part.contentType

            when (part.name) {
                "file" -> {
                    span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())

                    val filename = part.filename
                    val format = when {
                        contentType != null && contentType.type == "audio" -> contentType.subtype
                        filename != null -> filename.substringAfterLast('.', "").lowercase().ifEmpty { null }
                        else -> null
                    }
                    if (format != null) {
                        span.setAttribute("tracy.request.audio.format", format)
                    }
                }

                "model" -> {
                    val content = part.content.toString(
                        contentType?.charset() ?: Charsets.UTF_8
                    )
                    span.setAttribute(GEN_AI_REQUEST_MODEL, content)
                }

                "response_format" -> {
                    val content = part.content.toString(
                        contentType?.charset() ?: Charsets.UTF_8
                    )
                    span.setAttribute("tracy.request.response_format", content)
                }

                "timestamp_granularities[]" -> {
                    val content = part.content.toString(
                        contentType?.charset() ?: Charsets.UTF_8
                    )
                    timestampGranularities.add(content)
                }

                null -> logger.warn { "Form data part with missing name ignored. Content type: '$contentType'" }

                else -> {
                    if (contentType == null) {
                        logger.warn { "Missing content type of form data part '${part.name}'" }
                        continue
                    }
                    val content = when (contentType.type) {
                        "text" -> part.content.toString(contentType.charset() ?: Charsets.UTF_8)
                        else -> null
                    }
                    if (content != null) {
                        span.setAttribute("tracy.request.${part.name}", content)
                    }
                }
            }
        }

        if (timestampGranularities.isNotEmpty()) {
            span.setAttribute(
                "tracy.request.timestamp_granularities",
                timestampGranularities.joinToString(",")
            )
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // Response is JSON (json or verbose_json format)
        span.setAttribute("gen_ai.output.type", "json")

        body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute("tracy.response.transcription.duration_seconds", it)
        }

        body["language"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.transcription.language", it)
        }

        val words = body["words"]
        if (words is JsonArray) {
            span.setAttribute("tracy.response.transcription.words.count", words.jsonArray.size.toLong())
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio Transcription API does not use server-sent events streaming
        logger.warn { "Audio Transcription API does not use server-sent events streaming" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
