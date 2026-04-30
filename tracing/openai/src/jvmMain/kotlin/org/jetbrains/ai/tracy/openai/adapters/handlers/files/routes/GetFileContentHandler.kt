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
 * Handles `GET /v1/files/{file_id}/content` — retrieve the raw content of a file (`files.content`).
 *
 * Request: file_id extracted from the URL path → `tracy.request.file.id`.
 * Response: binary content; size from `Content-Length` header → `tracy.response.file.size_bytes`.
 *
 * See [Retrieve file content](https://platform.openai.com/docs/api-reference/files/retrieve-contents)
 */
internal class GetFileContentHandler : FileRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val fileId = extractFileIdFromPath(request.url)
        if (fileId != null) {
            span.setAttribute("tracy.request.file.id", fileId)
        } else {
            logger.warn { "Failed to extract file ID from URL: ${request.url}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        // Response body is binary file content; use Content-Length header for size
        response.contentLength?.let { span.setAttribute("tracy.response.file.size_bytes", it) }
    }
}
