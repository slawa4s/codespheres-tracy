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
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts request/response attributes for audio transcription and translation APIs.
 *
 * See [Audio Transcriptions API](https://platform.openai.com/docs/api-reference/audio/createTranscription)
 * See [Audio Translations API](https://platform.openai.com/docs/api-reference/audio/createTranslation)
 */
internal class AudioTranscriptionOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val formData = request.body.asFormData() ?: return

        for (part in formData.parts) {
            when (part.name) {
                "model" -> {
                    val content = part.content.toString(part.contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute(GEN_AI_REQUEST_MODEL, content)
                }
                "response_format" -> {
                    val content = part.content.toString(part.contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute("gen_ai.request.response_format", content)
                }
                "file" -> {
                    val format = deriveAudioFormat(part.filename, part.contentType?.subtype)
                    if (format != null) {
                        span.setAttribute("gen_ai.request.audio.format", format)
                    }
                    span.setAttribute("gen_ai.request.audio.size_bytes", part.content.size.toLong())
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["text"]?.jsonPrimitive?.content?.let { text ->
            span.setAttribute("gen_ai.response.text", text.orRedactedOutput())
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // audio transcription/translation does not use SSE streaming
    }

    private fun deriveAudioFormat(filename: String?, contentTypeSubtype: String?): String? {
        if (filename != null) {
            val extension = filename.substringAfterLast('.', "")
            if (extension.isNotEmpty()) return extension.lowercase()
        }
        return contentTypeSubtype?.lowercase()
    }
}
