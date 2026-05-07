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
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.util.*

/**
 * Extracts request/response bodies of the Audio Transcriptions API.
 *
 * See [Audio Transcriptions API](https://platform.openai.com/docs/api-reference/audio/createTranscription)
 */
internal class AudioTranscriptionOpenAIApiEndpointHandler(
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

            val content = when (contentType.type) {
                "audio" -> Base64.getEncoder().encodeToString(part.content)
                "text" -> part.content.toString(contentType.charset() ?: Charsets.US_ASCII)
                else -> null
            }

            if (content == null) {
                logger.warn { "Form data part '${part.name}' with content type '$contentType' has no content" }
                continue
            }

            when (part.name) {
                "model" -> span.setAttribute(GEN_AI_REQUEST_MODEL, content)

                "prompt" -> span.setAttribute("gen_ai.prompt.0.content", content.orRedactedInput())

                "language" -> span.setAttribute("gen_ai.request.language", content)

                "response_format" -> span.setAttribute("gen_ai.request.response_format", content)

                "temperature" -> span.setAttribute("gen_ai.request.temperature", content)

                "file" -> if (contentTracingAllowed(ContentKind.INPUT)) {
                    span.setAttribute("gen_ai.request.audio.content", content)
                    span.setAttribute("gen_ai.request.audio.content_type", contentType.asString())
                    if (part.filename != null) {
                        span.setAttribute("gen_ai.request.audio.filename", part.filename)
                    }
                    mediaContentParts.add(
                        MediaContentPart(resource = Resource.Base64(content, contentType.asString()))
                    )
                }

                null -> logger.warn { "Form data part with missing name ignored. Content type: '$contentType'" }
                else -> span.setAttribute("gen_ai.request.${part.name}", content.orRedactedInput())
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

        body["model"]?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it.jsonPrimitive.content) }

        body["text"]?.let {
            span.setAttribute("gen_ai.completion.0.content", it.jsonPrimitive.content.orRedactedOutput())
        }

        body["task"]?.let { span.setAttribute("gen_ai.response.task", it.jsonPrimitive.content) }

        body["language"]?.let { span.setAttribute("gen_ai.response.language", it.jsonPrimitive.content) }

        body["duration"]?.let {
            it.jsonPrimitive.content.toDoubleOrNull()?.let { duration ->
                span.setAttribute("gen_ai.response.duration", duration)
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Audio transcription streaming is not supported
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
