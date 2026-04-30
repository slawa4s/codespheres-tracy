/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

private val logger = KotlinLogging.logger {}

/**
 * Handles `GET /files/{file_id}` — retrieve file metadata.
 *
 * See [Retrieve file](https://platform.openai.com/docs/api-reference/files/retrieve)
 */
internal class RetrieveFileHandler : FileRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val fileId = extractFileIdFromPath(request.url)
        if (fileId != null) {
            span.setAttribute("tracy.request.file.id", fileId)
        } else {
            logger.warn { "Failed to extract file ID from URL: ${request.url}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.id", it) }
        body["filename"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.filename", it) }
        body["bytes"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.bytes", it) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.created_at", it) }
        body["purpose"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.purpose", it) }
        body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.status", it) }
    }
}
