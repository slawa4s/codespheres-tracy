/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the OpenAI Audio Translations API (`/v1/audio/translations`).
 *
 * Parses multipart form-data upload requests and JSON translation responses.
 * Extracts model, audio file metadata, response format, temperature, and prompt presence from
 * the request; extracts translation duration from the response.
 *
 * See [OpenAI Audio Translations API](https://platform.openai.com/docs/api-reference/audio/createTranslation)
 */
internal class AudioTranslationOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gen_ai.operation.name", "audio.translation")
        span.setAttribute("openai.api.type", "audio")

        val body = request.body.asFormData() ?: return

        for (part in body.parts) {
            val charset = part.contentType?.charset() ?: Charsets.UTF_8

            when (part.name) {
                "file" -> {
                    span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                    val format = part.filename
                        ?.substringAfterLast(".", "")
                        ?.takeIf { it.isNotEmpty() }
                        ?: part.contentType?.subtype
                    if (format != null) {
                        span.setAttribute("tracy.request.audio.format", format)
                    }
                }

                "model" -> {
                    val value = part.content.toString(charset)
                    span.setAttribute(GEN_AI_REQUEST_MODEL, value)
                }

                "response_format" -> {
                    val value = part.content.toString(charset)
                    span.setAttribute("tracy.request.response_format", value)
                    val outputType = when (value) {
                        "verbose_json", "json" -> "json"
                        "text" -> "text"
                        else -> null
                    }
                    if (outputType != null) {
                        span.setAttribute("gen_ai.output.type", outputType)
                    }
                }

                "temperature" -> {
                    val value = part.content.toString(charset).toDoubleOrNull()
                    if (value != null) {
                        span.setAttribute("tracy.request.temperature", value)
                    }
                }

                "prompt" -> {
                    span.setAttribute("tracy.request.prompt.present", true)
                }

                null -> logger.warn { "Audio translation form part with missing name ignored" }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute("tracy.response.translation.duration_seconds", it)
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
