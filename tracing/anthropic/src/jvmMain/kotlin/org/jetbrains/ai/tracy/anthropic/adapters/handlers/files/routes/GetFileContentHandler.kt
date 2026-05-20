/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

/**
 * Handles the `GET /v1/files/{file_id}/content` endpoint. Response body is binary.
 */
internal class GetFileContentHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // No request-side attributes.
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        // Binary response body; no JSON attributes to extract.
    }
}
