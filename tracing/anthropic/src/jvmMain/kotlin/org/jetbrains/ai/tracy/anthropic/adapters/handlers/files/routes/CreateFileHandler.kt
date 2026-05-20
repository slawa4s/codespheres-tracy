/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Upload File: Handles the `POST /v1/files` endpoint (multipart upload).
 *
 * See [files/upload](https://platform.claude.com/docs/en/api/beta/files/upload)
 */
internal class CreateFileHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val formData = request.body.asFormData() ?: return
        for (part in formData.parts) {
            if (part.name == "file") {
                span.setAttribute("gen_ai.request.file.size_bytes", part.content.size.toLong())
                part.filename?.let { span.setAttribute("gen_ai.request.file.filename", it) }
                part.contentType?.let {
                    span.setAttribute("gen_ai.request.file.mime_type", "${it.type}/${it.subtype}")
                }
                break
            }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceFileMetadata(body)
    }
}
