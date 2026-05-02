/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContent
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentPart
import org.jetbrains.ai.tracy.core.adapters.media.Resource
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.ContentKind
import org.jetbrains.ai.tracy.core.policy.contentTracingAllowed
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.util.*

/**
 * Extracts request/response bodies of Audio Transcriptions and Audio Translations APIs.
 *
 * Both endpoints use multipart form-data for requests and return a JSON body
 * with a `text` field containing the transcribed or translated content.
 *
 * See [Audio Transcriptions API](https://platform.openai.com/docs/api-reference/audio/createTranscription)
 * See [Audio Translations API](https://platform.openai.com/docs/api-reference/audio/createTranslation)
 */
internal class AudioOpenAIApiEndpointHandler(
    private val extractor: MediaContentExtractor
) : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asFormData() ?: return

        val mediaContentParts = mutableListOf<MediaContentPart>()

        for (part in body.parts) {
            val contentType = part.contentType
            if (contentType == null) {
                logger.warn { "Missing content type of form data part '${part.name}'" }
                continue
            }

            when (part.name) {
                "file" -> if (contentTracingAllowed(ContentKind.INPUT)) {
                    val content = Base64.getEncoder().encodeToString(part.content)
                    span.setAttribute("gen_ai.request.audio.file.content", content)
                    span.setAttribute("gen_ai.request.audio.file.contentType", contentType.asString())
                    if (part.filename != null) {
                        span.setAttribute("gen_ai.request.audio.file.filename", part.filename)
                    }
                    mediaContentParts.add(
                        MediaContentPart(resource = Resource.Base64(content, contentType.asString()))
                    )
                }

                "model" -> {
                    val content = part.content.toString(contentType.charset() ?: Charsets.US_ASCII)
                    span.setAttribute(GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL, content)
                }

                "prompt" -> {
                    val content = part.content.toString(contentType.charset() ?: Charsets.US_ASCII)
                    span.setAttribute("gen_ai.prompt.0.content", content.orRedactedInput())
                }

                "language" -> {
                    val content = part.content.toString(contentType.charset() ?: Charsets.US_ASCII)
                    span.setAttribute("gen_ai.request.audio.language", content)
                }

                "response_format" -> {
                    val content = part.content.toString(contentType.charset() ?: Charsets.US_ASCII)
                    span.setAttribute("gen_ai.request.audio.response_format", content)
                }

                "temperature" -> {
                    val content = part.content.toString(contentType.charset() ?: Charsets.US_ASCII)
                    content.toDoubleOrNull()?.let {
                        span.setAttribute(GenAiIncubatingAttributes.GEN_AI_REQUEST_TEMPERATURE, it)
                    }
                }

                null -> logger.warn { "Form data part with missing name ignored. Content type: '$contentType'" }
                else -> {
                    val decoded = when (contentType.type) {
                        "text" -> part.content.toString(contentType.charset() ?: Charsets.US_ASCII)
                        else -> Base64.getEncoder().encodeToString(part.content)
                    }
                    span.setAttribute("gen_ai.request.${part.name}", decoded.orRedactedInput())
                }
            }
        }

        if (contentTracingAllowed(ContentKind.INPUT) && mediaContentParts.isNotEmpty()) {
            extractor.setUploadableContentAttributes(
                span,
                field = "input",
                content = MediaContent(mediaContentParts),
            )
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["text"]?.jsonPrimitive?.content?.let { text ->
            span.setAttribute("gen_ai.completion.0.content", text.orRedactedOutput())
        }

        body["language"]?.jsonPrimitive?.content?.let { language ->
            span.setAttribute("gen_ai.response.audio.language", language)
        }

        body["duration"]?.jsonPrimitive?.content?.let { duration ->
            span.setAttribute("gen_ai.response.audio.duration", duration)
        }

        body["task"]?.jsonPrimitive?.content?.let { task ->
            span.setAttribute(GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME, task)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio transcription and translation endpoints do not support streaming
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
