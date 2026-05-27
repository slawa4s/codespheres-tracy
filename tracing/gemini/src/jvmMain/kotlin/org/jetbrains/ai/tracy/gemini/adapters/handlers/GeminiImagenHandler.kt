/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.handlers.sse.sseHandlingUnsupported
import org.jetbrains.ai.tracy.core.adapters.media.MediaContent
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentPart
import org.jetbrains.ai.tracy.core.adapters.media.Resource
import org.jetbrains.ai.tracy.core.http.parsers.SseEvent
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput

/**
 * Parses Imagen API requests and responses
 *
 * See: [Imagen API Docs](https://ai.google.dev/gemini-api/docs/imagen)
 */
class GeminiImagenHandler(
    private val extractor: MediaContentExtractor
) : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        // body: { "instances": [..], "parameters": [..] }
        val body = request.body.asJson()?.jsonObject ?: return

        val instancesEntry = body["instances"]
        if (instancesEntry == null || instancesEntry !is JsonArray) {
            return
        }
        val instances = instancesEntry.jsonArray

        for ((index, instance) in instances.withIndex()) {
            span.setAttribute(
                "gen_ai.prompt.$index.content",
                instance.jsonObject["prompt"]?.jsonPrimitive?.content?.orRedactedInput(),
            )
        }

        body["parameters"]?.let { span.setAttribute("tracy.request.imagen.parameters", it.toString()) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        val predictions = body["predictions"]?.jsonArray ?: return
        for ((index, prediction) in predictions.withIndex()) {
            span.setAttribute(
                "gen_ai.completion.$index.content",
                prediction.jsonObject["prompt"]?.jsonPrimitive?.content?.orRedactedOutput(),
            )
        }
        val resources = parseImagenImages(predictions)

        // setting generated images for upload
        val mediaContent = MediaContent(resources.map { MediaContentPart(it) })
        extractor.setUploadableContentAttributes(span, field = "output", mediaContent)
    }

    override fun handleStreamingEvent(
        span: Span,
        event: SseEvent,
        index: Long
    ): Result<Unit> {
        return sseHandlingUnsupported()
    }

    /**
     * Expects an array of schemas:
     * ```json
     * {
     *    "mimeType": "string",
     *    "bytesBase64Encoded": "string"
     * }
     * ```
     */
    private fun parseImagenImages(images: JsonArray): List<Resource> = buildList {
        for (image in images) {
            val resource = parseImagenImage(image.jsonObject) ?: continue
            add(resource)
        }
    }

    private fun parseImagenImage(image: JsonObject): Resource? {
        val mimeType = image["mimeType"]?.jsonPrimitive?.content ?: return null
        val base64 = image["bytesBase64Encoded"]?.jsonPrimitive?.content ?: return null

        // NOTE: mediaType == mimeType when parameters are empty
        return Resource.Base64(base64, mimeType)
    }
}