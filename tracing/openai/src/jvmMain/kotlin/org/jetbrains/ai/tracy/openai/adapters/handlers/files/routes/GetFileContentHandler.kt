/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

/**
 * Handles the `GET /files/{file_id}/content` endpoint.
 */
internal class GetFileContentHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractFileIdFromPath(request.url)?.let {
            span.setAttribute("tracy.request.file_id", it)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        // TODO: response body is the raw file bytes — non-JSON response types are not yet
        //   first-class in Tracy's TracyHttpResponse. Trace MIME type and size in bytes once
        //   they become available.
    }
}
