/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

private val logger = KotlinLogging.logger {}

/**
 * Handles `DELETE /v1/files/{file_id}` — delete a file (`files.delete`).
 *
 * Response: `id` → `tracy.response.file.id`, `deleted` → `tracy.response.deleted`.
 *
 * See [Delete file](https://platform.openai.com/docs/api-reference/files/delete)
 */
internal class DeleteFileHandler : FileRouteHandler {
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
        body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.deleted", it.toString()) }
    }
}
