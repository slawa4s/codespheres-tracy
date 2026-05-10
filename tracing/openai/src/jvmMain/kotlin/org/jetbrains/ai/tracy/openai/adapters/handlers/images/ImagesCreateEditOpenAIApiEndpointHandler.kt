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
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
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
        span.setAttribute(GEN_AI_OPERATION_NAME, "generate_content")
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "image")

        val body = request.body.asFormData() ?: return

        val mediaContentParts = mutableListOf<MediaContentPart>()
        var imagesCount = 0

        for (part in body.parts) {
            // TODO: TRACY-88
            val contentType = part.contentType
            if (contentType == null) {
                logger.warn { "Missing content type of form data part '${part.name}'" }
                continue
            }

            // decode content based on the expected content type
            val content = when(contentType.type) {
                "image" -> Base64.getEncoder().encodeToString(part.content)
                "text" -> part.content.toString(
                    contentType.charset() ?: Charsets.US_ASCII
                )
                else -> null
            }

            if (content == null) {
                logger.warn { "Form data part '${part.name}' with content type '$contentType' has no content" }
                continue
            }

            when (part.name) {
                "prompt" -> {
                    span.setAttribute("gen_ai.prompt.0.content", content.orRedactedInput())
                }

                "model" -> {
                    span.setAttribute(GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL, content)
                }
                // mask is a single image that should be uploaded as well
                "mask" -> if (contentTracingAllowed(ContentKind.INPUT)) {
                    // trace mask only when input content tracing is allowed.
                    // base64-encoded mask content
                    span.setAttribute("tracy.request.mask.content", content)
                    span.setAttribute("tracy.request.mask.contentType", contentType.asString())
                    if (part.filename != null) {
                        span.setAttribute("tracy.request.mask.filename", part.filename)
                    }
                    // save mask for further upload
                    mediaContentParts.add(
                        MediaContentPart(resource = Resource.Base64(content, contentType.asString()))
                    )
                }
                // either a single image or an array of images
                "image", "image[]" -> {
                    span.setAttribute("tracy.request.image.size_bytes", part.content.size.toLong())
                    if (contentTracingAllowed(ContentKind.INPUT)) {
                        // trace images only when input content tracing is allowed.
                        // base64-encoded image content
                        span.setAttribute("gen_ai.request.image.$imagesCount.content", content)
                        span.setAttribute("gen_ai.request.image.$imagesCount.contentType", contentType.asString())
                        if (part.filename != null) {
                            span.setAttribute("gen_ai.request.image.$imagesCount.filename", part.filename)
                        }
                        // save image for further upload
                        mediaContentParts.add(
                            MediaContentPart(resource = Resource.Base64(content, contentType.asString()))
                        )
                        ++imagesCount
                    }
                }

                "n" -> content.toLongOrNull()?.let { span.setAttribute("tracy.request.n", it) }
                    ?: span.setAttribute("tracy.request.n", content.orRedactedInput())
                "partial_images" -> content.toLongOrNull()?.let { span.setAttribute("tracy.request.partial_images", it) }
                    ?: span.setAttribute("tracy.request.partial_images", content.orRedactedInput())
                "stream" -> content.toBooleanStrictOrNull()?.let { span.setAttribute("gen_ai.request.stream", it) }
                null -> logger.warn { "Form data part with missing name ignored. Content type: '$contentType'" }
                else -> {
                    // since we don't know how sensitive other fields may be,
                    // we disguise their content if input tracing is disallowed.
                    span.setAttribute("tracy.request.${part.name}", content.orRedactedInput())
                }
            }
        }

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
        var foundAnyEvent = false
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

            foundAnyEvent = true
            handleStreamedImage(
                span, data, extractor,
                completedType = "image_edit.completed",
                partialImageType = "image_edit.partial_image",
            )
        }

        // Fallback: proxy returned a non-SSE JSON response body for a streaming request.
        if (!foundAnyEvent && events.isNotBlank()) {
            val body = try {
                Json.parseToJsonElement(events.trim()) as? JsonObject
            } catch (_: Exception) { null } ?: return

            (body["usage"] as? JsonObject)?.let { usage ->
                (usage["input_tokens"] as? JsonPrimitive)?.intOrNull?.let {
                    span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
                }
                (usage["output_tokens"] as? JsonPrimitive)?.intOrNull?.let {
                    span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it)
                }
            }

            ((body["created_at"] ?: body["created"]) as? JsonPrimitive)?.longOrNull?.let {
                span.setAttribute("tracy.response.created_at", it)
            }

            (body["data"] as? JsonArray)?.firstOrNull()?.let { first ->
                if (first is JsonObject) {
                    (first["b64_json"] as? JsonPrimitive)?.content?.let { b64 ->
                        val content = Json.parseToJsonElement("""{"b64_json": "$b64"}""")
                        span.setAttribute("gen_ai.completion.0.content", content.toString())
                    }
                    (first["url"] as? JsonPrimitive)?.content?.let {
                        span.setAttribute("tracy.response.image.url", it)
                    }
                }
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}