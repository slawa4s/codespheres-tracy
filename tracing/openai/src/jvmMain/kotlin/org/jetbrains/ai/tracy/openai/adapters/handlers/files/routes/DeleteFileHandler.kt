/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `DELETE /files/{file_id}` endpoint.
 */
internal class DeleteFileHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractFileIdFromPath(request.url)?.let {
            span.setAttribute("tracy.request.file_id", it)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.id", it)
        }
        body["deleted"]?.let {
            span.setAttribute("tracy.response.deleted", it.jsonPrimitive.boolean)
        }
        body["object"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.object", it)
        }
    }
}
