/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `POST /files` endpoint (multipart upload).
 */
internal class CreateFileHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asFormData() ?: return
        for (part in body.parts) {
            val partName = part.name ?: continue
            val charset = part.contentType?.charset() ?: Charsets.UTF_8
            when (partName) {
                "file" -> {
                    span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                    part.contentType?.let { ct ->
                        span.setAttribute("tracy.request.file.mime_type", "${ct.type}/${ct.subtype}")
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
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceFileObject(body)
    }
}
