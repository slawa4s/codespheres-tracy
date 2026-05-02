/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the OpenAI Files API endpoints:
 * - POST /v1/files
 * - GET /v1/files
 * - GET /v1/files/{file_id}
 * - DELETE /v1/files/{file_id}
 * - GET /v1/files/{file_id}/content
 *
 * Extracts file-specific attributes from the response body using the `tracy.response.file.*`
 * prefix. The `populateUnmappedAttributes` helper cannot produce this prefix automatically
 * because the top-level response fields (e.g. `id`, `bytes`) carry no `file.` namespace.
 *
 * See [Files API](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val segments = request.url.pathSegments
        val method = request.method.uppercase()
        val operationName = when (method) {
            "DELETE" -> "files.delete"
            "POST" -> "files.create"
            "GET" -> {
                val filesIndex = segments.indexOf("files")
                when {
                    filesIndex >= 0 && segments.getOrNull(filesIndex + 1) != null &&
                            segments.getOrNull(filesIndex + 2) == "content" -> "files.content"
                    filesIndex >= 0 && segments.getOrNull(filesIndex + 1) != null -> "files.retrieve"
                    else -> "files.list"
                }
            }
            else -> null
        }
        operationName?.let { span.setAttribute(GEN_AI_OPERATION_NAME, it) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.id", it) }
        body["created_at"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.created_at", it) }
        body["expires_at"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.expires_at", it) }
        body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.status", it) }
        body["filename"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.filename", it) }
        body["bytes"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.size_bytes", it) }
        body["purpose"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.purpose", it) }
    }

    override fun handleStreaming(span: Span, events: String) {
        // The Files API does not support server-sent events streaming.
    }
}
