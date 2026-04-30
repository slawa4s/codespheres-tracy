/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

private val logger = KotlinLogging.logger {}

/**
 * Handles [FilesOpenAIApiEndpointHandler.FileRoute.FILE_CONTENT] endpoint: `GET /v1/files/{file_id}/content`.
 */
internal class GetFileContentHandler : FileRouteHandler {
    /**
     * Request: Path parameter file_id extracted from URL segment after "files".
     */
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val fileId = extractFileIdFromPath(request.url)
        if (fileId != null) {
            span.setAttribute("tracy.request.file.id", fileId)
        } else {
            logger.warn { "Failed to extract file ID from URL: ${request.url}" }
        }
    }

    /**
     * Response: Binary file content (trace size from Content-Length header).
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        response.contentLength?.let { sizeBytes ->
            span.setAttribute("tracy.response.file.size_bytes", sizeBytes)
        }
        val contentType = response.contentType
        if (contentType != null) {
            span.setAttribute("gen_ai.response.content_type", contentType.asString())
        }
    }
}
