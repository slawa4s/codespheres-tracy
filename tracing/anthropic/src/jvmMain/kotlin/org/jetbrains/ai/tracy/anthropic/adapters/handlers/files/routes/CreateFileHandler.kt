/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.files.routes

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
 * Upload File: Handles the `POST /v1/files` endpoint (multipart upload).
 *
 * See [files/upload](https://platform.claude.com/docs/en/api/beta/files/upload)
 */
internal class CreateFileHandler(
    private val extractor: MediaContentExtractor,
) : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val formData = request.body.asFormData() ?: return
        for (part in formData.parts) {
            if (part.name != "file") {
                continue
            }

            span.setAttribute("gen_ai.request.file.size_bytes", part.content.size.toLong())
            part.filename?.let { span.setAttribute("gen_ai.request.file.filename", it) }
            val mimeType = part.contentType?.let { "${it.type}/${it.subtype}" }
            mimeType?.let { span.setAttribute("gen_ai.request.file.mime_type", it) }

            if (mimeType != null && contentTracingAllowed(ContentKind.INPUT)) {
                val base64 = Base64.getEncoder().encodeToString(part.content)
                extractor.setUploadableContentAttributes(
                    span,
                    field = "input",
                    content = MediaContent(
                        parts = listOf(MediaContentPart(Resource.Base64(base64, mimeType)))
                    ),
                )
            }
            break
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceFileMetadata(body)
    }
}
