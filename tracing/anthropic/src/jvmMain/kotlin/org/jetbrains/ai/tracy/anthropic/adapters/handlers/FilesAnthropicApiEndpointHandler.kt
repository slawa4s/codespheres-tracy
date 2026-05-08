/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic Files API.
 *
 * Maps HTTP method + URL path to `gen_ai.operation.name`:
 * - `POST   /v1/files`                    → `"files.upload"`
 * - `GET    /v1/files`                    → `"files.list"`
 * - `GET    /v1/files/{file_id}`          → `"files.retrieve"`
 * - `DELETE /v1/files/{file_id}`          → `"files.delete"`
 * - `GET    /v1/files/{file_id}/content`  → `"files.content"`
 *
 * For upload, extracts multipart form-data fields and sets `tracy.request.file.size_bytes`
 * and `tracy.request.file.filename`.
 *
 * For list, extracts pagination query parameters.
 *
 * For responses, maps [FileMetadata](https://docs.anthropic.com/en/api/files-create) fields
 * to `tracy.file.*` attributes.
 *
 * See: [Files API](https://docs.anthropic.com/en/api/files-create)
 */
internal class FilesAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "files")

        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val segments = request.url.pathSegments
        val filesIndex = segments.indexOf("files")

        // Extract file_id from URL path for non-UPLOAD and non-LIST routes
        if (route != FileRoute.UPLOAD && route != FileRoute.LIST &&
            filesIndex != -1 && segments.size > filesIndex + 1
        ) {
            val fileId = segments[filesIndex + 1]
            if (fileId.isNotBlank()) {
                span.setAttribute("tracy.file.id", fileId)
            }
        }

        when (route) {
            FileRoute.UPLOAD -> {
                // POST /v1/files uses multipart form data with a `file` field
                val body = request.body.asFormData() ?: return
                for (part in body.parts) {
                    if (part.name == "file") {
                        span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                        val filename = part.filename
                        if (filename != null) {
                            span.setAttribute("tracy.request.file.filename", filename)
                        }
                    }
                }
            }

            FileRoute.LIST -> {
                // GET /v1/files accepts query parameters for pagination
                val params = request.url.parameters
                params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
                params.queryParameter("after_id")?.let { span.setAttribute("tracy.request.after_id", it) }
                params.queryParameter("before_id")?.let { span.setAttribute("tracy.request.before_id", it) }
            }

            else -> {
                // RETRIEVE, DELETE, CONTENT: file_id already extracted from URL above
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val route = detectRoute(response.url, response.requestMethod)

        when (route) {
            FileRoute.LIST -> {
                // Response: { data: [...], first_id, last_id, has_more }
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("tracy.file.count", data.size.toLong())
                }
            }

            FileRoute.DELETE -> {
                // Response: DeletedFile { id, type: "file_deleted" }
                body["id"]?.let { span.setAttribute("tracy.file.id", it.jsonPrimitive.content) }
            }

            FileRoute.CONTENT -> {
                // Response body is binary (file content), no JSON attributes to extract
            }

            else -> {
                // FileRoute.UPLOAD, FileRoute.RETRIEVE
                // Response: FileMetadata { id, created_at, filename, mime_type, size_bytes, type }
                body["id"]?.let {
                    val id = it.jsonPrimitive.content
                    span.setAttribute("tracy.file.id", id)
                    span.setAttribute(GEN_AI_RESPONSE_ID, id)
                }
                body["filename"]?.let { span.setAttribute("tracy.file.filename", it.jsonPrimitive.content) }
                body["mime_type"]?.let { span.setAttribute("tracy.file.mime_type", it.jsonPrimitive.content) }
                body["size_bytes"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.file.size_bytes", it) }
                body["created_at"]?.let { span.setAttribute("tracy.file.created_at", it.jsonPrimitive.content) }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Files API does not use SSE streaming
    }

    /**
     * Detects which specific Files API endpoint is being called based on the URL path and HTTP method.
     *
     * URL patterns:
     * - `POST   .../files`                   → [FileRoute.UPLOAD]
     * - `GET    .../files`                   → [FileRoute.LIST]
     * - `GET    .../files/{file_id}`         → [FileRoute.RETRIEVE]
     * - `DELETE .../files/{file_id}`         → [FileRoute.DELETE]
     * - `GET    .../files/{file_id}/content` → [FileRoute.CONTENT]
     */
    internal fun detectRoute(url: TracyHttpUrl, method: String): FileRoute {
        val segments = url.pathSegments
        val filesIndex = segments.indexOf("files")

        if (filesIndex == -1) {
            logger.warn { "No 'files' segment in URL path: ${segments.joinToString("/")}" }
            return FileRoute.UPLOAD
        }

        val afterFiles = segments.drop(filesIndex + 1).filter { it.isNotBlank() }
        val hasFileId = afterFiles.isNotEmpty() && afterFiles.first() != "content"
        val hasContent = afterFiles.contains("content")

        return when {
            method == "POST" && !hasFileId -> FileRoute.UPLOAD
            method == "GET" && !hasFileId -> FileRoute.LIST
            method == "GET" && hasFileId && hasContent -> FileRoute.CONTENT
            method == "GET" && hasFileId -> FileRoute.RETRIEVE
            method == "DELETE" && hasFileId -> FileRoute.DELETE
            else -> {
                logger.warn { "Unknown files operation: $method /${segments.joinToString("/")}" }
                FileRoute.UPLOAD
            }
        }
    }

    /**
     * Internal enum to distinguish between different Files API routes.
     */
    internal enum class FileRoute(val operationName: String) {
        UPLOAD("files.upload"),
        LIST("files.list"),
        RETRIEVE("files.retrieve"),
        DELETE("files.delete"),
        CONTENT("files.content"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
