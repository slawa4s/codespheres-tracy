/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.images

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContent
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentPart
import org.jetbrains.ai.tracy.core.adapters.media.Resource
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.policy.ContentKind
import org.jetbrains.ai.tracy.core.policy.contentTracingAllowed
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import java.util.*

/**
 * Extracts request/response bodies of Image Edit API.
 *
 * See [Image Edit API](https://platform.openai.com/docs/api-reference/images/createEdit)
 */
internal class ImagesCreateEditOpenAIApiEndpointHandler(
    private val extractor: MediaContentExtractor
) : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        // Attempt to capture model via JSON path first (mirrors the pattern in other handlers).
        // For multipart/form-data requests this is a no-op; the model is set below via form fields.
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        val body = request.body.asFormData() ?: return

        val mediaContentParts = mutableListOf<MediaContentPart>()
        var imagesCount = 0
        var modelSetFromForm = false
        var sizeSetFromForm = false
        var nSetFromForm = false
        var responseFormatSetFromForm = false

        for (part in body.parts) {
            // Parts without a Content-Type header are text fields (e.g. model, prompt, n, size).
            // Treat them as text/plain decoded with US-ASCII so the when-block below can handle them.
            val contentType = part.contentType

            // decode content based on the expected content type
            val content = when (contentType?.type) {
                "image" -> Base64.getEncoder().encodeToString(part.content)
                "text" -> part.content.toString(
                    contentType.charset() ?: Charsets.US_ASCII
                )
                null -> part.content.toString(Charsets.US_ASCII)
                // For any other MIME type (e.g. application/octet-stream) treat the bytes as
                // opaque binary and base64-encode them so that subsequent form-field handling
                // (model, prompt, size, etc.) is not skipped via the null-continue guard.
                else -> Base64.getEncoder().encodeToString(part.content)
            }

            when (part.name) {
                "prompt" -> {
                    span.setAttribute("gen_ai.prompt.0.content", content.orRedactedInput())
                }

                "model" -> {
                    span.setAttribute(GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL, content)
                    modelSetFromForm = true
                }
                // mask is a single image that should be uploaded as well
                "mask" -> if (contentTracingAllowed(ContentKind.INPUT)) {
                    // trace mask only when input content tracing is allowed.
                    // base64-encoded mask content
                    span.setAttribute("gen_ai.request.mask.content", content)
                    if (contentType != null) {
                        span.setAttribute("gen_ai.request.mask.contentType", contentType.asString())
                    }
                    span.setAttribute("gen_ai.request.mask.size_bytes", part.content.size.toLong())
                    if (part.filename != null) {
                        span.setAttribute("gen_ai.request.mask.filename", part.filename)
                    }
                    // save mask for further upload
                    if (contentType != null) {
                        mediaContentParts.add(
                            MediaContentPart(resource = Resource.Base64(content, contentType.asString()))
                        )
                    }
                }
                // either a single image or an array of images
                "image", "image[]" -> {
                    // image byte count is metadata, not user content — always record it
                    span.setAttribute("gen_ai.request.image.size_bytes", part.content.size.toLong())
                    if (contentTracingAllowed(ContentKind.INPUT)) {
                        // trace images only when input content tracing is allowed.
                        // base64-encoded image content
                        span.setAttribute("gen_ai.request.image.$imagesCount.content", content)
                        if (contentType != null) {
                            span.setAttribute("gen_ai.request.image.$imagesCount.contentType", contentType.asString())
                        }
                        if (part.filename != null) {
                            span.setAttribute("gen_ai.request.image.$imagesCount.filename", part.filename)
                        }
                        // save image for further upload
                        if (contentType != null) {
                            mediaContentParts.add(
                                MediaContentPart(resource = Resource.Base64(content, contentType.asString()))
                            )
                        }
                    }
                    ++imagesCount
                }

                // Configuration metadata — not user-supplied content, set directly without redaction.
                "size" -> { span.setAttribute("gen_ai.request.size", content); sizeSetFromForm = true }
                "n" -> { span.setAttribute("gen_ai.request.n", content); nSetFromForm = true }
                "response_format" -> { span.setAttribute("gen_ai.request.response_format", content); responseFormatSetFromForm = true }

                null -> logger.warn { "Form data part with missing name ignored. Content type: '$contentType'" }
                else -> {
                    // since we don't know how sensitive other fields may be,
                    // we disguise their content if input tracing is disallowed.
                    span.setAttribute("gen_ai.request.${part.name}", content.orRedactedInput())
                }
            }
        }

        // Fallback: some SDK versions serialise model, size, n, and response_format as query parameters
        // rather than multipart form fields (e.g. when set on the outer ImageEditParams.Builder).
        if (!modelSetFromForm) request.url.parameters.queryParameter("model")?.let { span.setAttribute(GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL, it) }
        if (!sizeSetFromForm) request.url.parameters.queryParameter("size")?.let { span.setAttribute("gen_ai.request.size", it) }
        if (!nSetFromForm) request.url.parameters.queryParameter("n")?.let { span.setAttribute("gen_ai.request.n", it) }
        if (!responseFormatSetFromForm) request.url.parameters.queryParameter("response_format")?.let { span.setAttribute("gen_ai.request.response_format", it) }

        if (contentTracingAllowed(ContentKind.INPUT)) {
            extractor.setUploadableContentAttributes(
                span,
                field = "input",
                content = MediaContent(mediaContentParts),
            )
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        handleImageGenerationResponseAttributes(span, response, extractor)
    }

    override fun handleStreaming(span: Span, events: String) {
        for (line in events.lineSequence()) {
            if (!line.startsWith("data:")) {
                continue
            }
            val data = try {
                Json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject
            } catch (err: Exception) {
                logger.trace("Failed to parse streaming data: '$line'", err)
                null
            } ?: continue

            handleStreamedImage(
                span, data, extractor,
                completedType = "image_edit.completed",
                partialImageType = "image_edit.partial_image",
            )
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}