/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
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
import java.util.Base64

/**
 * Handles the `POST /files` endpoint (multipart upload).
 */
internal class CreateFileHandler(
    private val extractor: MediaContentExtractor,
) : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asFormData() ?: return
        val mediaContentParts = mutableListOf<MediaContentPart>()
        for (part in body.parts) {
            val partName = part.name ?: continue
            val charset = part.contentType?.charset() ?: Charsets.UTF_8
            when (partName) {
                "file" -> {
                    span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                    val mimeType = part.contentType?.let { "${it.type}/${it.subtype}" }
                    mimeType?.let { span.setAttribute("tracy.request.file.mime_type", it) }

                    // Upload file bytes for Langfuse rendering — gated by input capture policy.
                    if (mimeType != null && contentTracingAllowed(ContentKind.INPUT)) {
                        val base64 = Base64.getEncoder().encodeToString(part.content)
                        mediaContentParts.add(MediaContentPart(Resource.Base64(base64, mimeType)))
                    }
                }
                "purpose" -> {
                    span.setAttribute("tracy.request.purpose", part.content.toString(charset))
                }
                "expires_after" -> {
                    span.setAttribute("tracy.request.expires_after", part.content.toString(charset))
                }
            }
        }

        if (mediaContentParts.isNotEmpty()) {
            extractor.setUploadableContentAttributes(
                span,
                field = "input",
                content = MediaContent(parts = mediaContentParts),
            )
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceFileObject(body)
    }
}
