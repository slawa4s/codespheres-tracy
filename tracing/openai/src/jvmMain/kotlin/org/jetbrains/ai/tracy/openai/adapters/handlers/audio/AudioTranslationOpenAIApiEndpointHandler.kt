/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_TEMPERATURE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

/**
 * Extracts request/response attributes for the OpenAI Audio Translations API.
 *
 * See [Create translation API](https://developers.openai.com/api/reference/resources/audio/subresources/translations/methods/create)
 */
internal class AudioTranslationOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)

        val body = request.body.asFormData() ?: return

        for (part in body.parts) {
            val contentType = part.contentType
            val partName = part.name ?: continue

            when {
                // Audio file part: extract size and format
                partName == "file" -> {
                    span.setAttribute("tracy.request.audio.size_bytes", part.content.size.toLong())
                    val format = contentType?.subtype
                        ?: part.filename?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
                    if (format != null) {
                        span.setAttribute("tracy.request.audio.format", format)
                    }
                }

                partName == "model" -> {
                    val content = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute(GEN_AI_REQUEST_MODEL, content)
                }

                partName == "response_format" -> {
                    val content = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute("tracy.request.response_format", content)
                    if (content == "json" || content == "verbose_json") {
                        span.setAttribute(GEN_AI_OUTPUT_TYPE, "json")
                    }
                }

                partName == "temperature" -> {
                    val content = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    content.toDoubleOrNull()?.let { span.setAttribute(GEN_AI_REQUEST_TEMPERATURE, it) }
                }

                partName == "prompt" -> {
                    span.setAttribute("tracy.request.prompt.present", true)
                }

                else -> {
                    logger.trace { "Unhandled audio translation form part: '$partName'" }
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_RESPONSE_MODEL, it)
        }
        body["duration"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute("tracy.response.translation.duration_seconds", it)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio translation endpoint does not support SSE streaming
    }

    companion object {
        private const val OPERATION_NAME = "audio.translation"
        private val logger = KotlinLogging.logger {}
    }
}
