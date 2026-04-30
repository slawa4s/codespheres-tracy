/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import kotlinx.serialization.json.jsonObject

/**
 * Handles the `files.create` endpoint: `POST /files`.
 *
 * Request is `multipart/form-data` with:
 * - `purpose` text part → `tracy.request.purpose`
 * - `file` part: filename → `tracy.request.file.filename`, content size → `tracy.request.file.size_bytes`
 *
 * Response is a File object.
 */
internal class CreateFileHandler : FileRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val formData = request.body.asFormData() ?: return
        for (part in formData.parts) {
            when (part.name) {
                "purpose" -> {
                    span.setAttribute("tracy.request.purpose", part.content.toString(Charsets.UTF_8))
                }
                "file" -> {
                    part.filename?.let { span.setAttribute("tracy.request.file.filename", it) }
                    span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                }
            }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceFileModel(body, "tracy.response.file")
    }
}
