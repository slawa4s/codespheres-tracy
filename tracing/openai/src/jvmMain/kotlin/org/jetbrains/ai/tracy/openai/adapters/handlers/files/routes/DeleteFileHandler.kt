/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `files.delete` endpoint: `DELETE /files/{file_id}`.
 *
 * Response: `id` → `tracy.response.file.id`.
 */
internal class DeleteFileHandler : FileRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // file_id is a path parameter; no request body attributes to extract
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let { span.setAttribute("tracy.response.file.id", it.jsonPrimitive.content) }
    }
}
