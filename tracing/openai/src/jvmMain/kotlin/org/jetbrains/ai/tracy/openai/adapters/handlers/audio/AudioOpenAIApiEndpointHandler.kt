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
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

/**
 * Extracts request/response attributes for the OpenAI Audio Transcriptions and Translations APIs.
 *
 * See [Audio Transcriptions API](https://platform.openai.com/docs/api-reference/audio/createTranscription)
 * See [Audio Translations API](https://platform.openai.com/docs/api-reference/audio/createTranslation)
 *
 * @param operationName Either "audio.transcription" or "audio.translation", set unconditionally on each span.
 */
internal class AudioOpenAIApiEndpointHandler(
    private val operationName: String,
) : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)

        val formData = request.body.asFormData() ?: return

        for (part in formData.parts) {
            when (part.name) {
                "model" -> {
                    val charset = part.contentType?.charset() ?: Charsets.UTF_8
                    val model = part.content.toString(charset)
                    span.setAttribute(GEN_AI_REQUEST_MODEL, model)
                }

                "response_format" -> {
                    val charset = part.contentType?.charset() ?: Charsets.UTF_8
                    val format = part.content.toString(charset)
                    span.setAttribute("gen_ai.request.response_format", format)
                }

                "file" -> {
                    val audioFormat = resolveAudioFormat(part.contentType?.subtype, part.filename)
                    if (audioFormat != null) {
                        span.setAttribute("gen_ai.request.audio.format", audioFormat)
                    }
                    span.setAttribute(
                        AttributeKey.longKey("gen_ai.request.audio.size_bytes"),
                        part.content.size.toLong(),
                    )
                }

                else -> {
                    // ignore other fields
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["text"]?.let { span.setAttribute("gen_ai.response.text", it.jsonPrimitive.content) }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio transcription/translation responses are not streamed; no-op.
    }

    /**
     * Resolves the audio format string from either a content-type subtype (e.g. "mpeg" → "mp3")
     * or from the filename extension (e.g. "recording.mp3" → "mp3").
     */
    private fun resolveAudioFormat(contentTypeSubtype: String?, filename: String?): String? {
        if (contentTypeSubtype != null) {
            return when (contentTypeSubtype.lowercase()) {
                "mpeg" -> "mp3"
                "x-m4a" -> "m4a"
                "x-wav" -> "wav"
                else -> contentTypeSubtype.lowercase()
            }
        }
        if (filename != null) {
            val ext = filename.substringAfterLast('.', missingDelimiterValue = "")
            if (ext.isNotEmpty()) {
                return ext.lowercase()
            }
        }
        logger.warn { "Could not determine audio format: no content-type subtype and no filename extension" }
        return null
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
