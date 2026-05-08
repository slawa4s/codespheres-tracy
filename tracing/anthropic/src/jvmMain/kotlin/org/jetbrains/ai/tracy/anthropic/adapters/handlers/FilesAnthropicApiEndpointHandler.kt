/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
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
 * - `POST   /v1/files`                    → `"files.create"`
 * - `GET    /v1/files`                    → `"files.list"`
 * - `GET    /v1/files/{file_id}`          → `"files.retrieve"`
 * - `DELETE /v1/files/{file_id}`          → `"files.delete"`
 * - `GET    /v1/files/{file_id}/content`  → `"files.content"`
 *
 * For CREATE and RETRIEVE routes, extracts file metadata from the response and sets:
 * - `gen_ai.response.id`            → file `id`
 * - `gen_ai.response.file.id`       → file `id`
 * - `gen_ai.response.file.filename` → file name
 * - `gen_ai.response.file.mime_type`  → MIME type
 * - `gen_ai.response.file.size_bytes` → size in bytes
 * - `gen_ai.response.file.created_at` → creation timestamp
 * - `gen_ai.response.file.downloadable` → whether the file is downloadable
 *
 * For DELETE routes, extracts from the response (`{"id": "file_...", "deleted": true, "type": "file_deleted"}`):
 * - `gen_ai.response.id`          → file `id`
 * - `gen_ai.response.file.id`     → file `id`
 * - `gen_ai.response.file.deleted` → deletion flag
 *
 * See: [Files API](https://docs.anthropic.com/en/api/files)
 */
internal class FilesAnthropicApiEndpointHandler : EndpointApiHandler {

    /**
     * Stores the detected route during request handling so it can be re-applied in
     * [handleResponseAttributes] without re-parsing the URL.
     *
     * A [ThreadLocal] is used to avoid shared mutable state between concurrent requests.
     */
    private val routeThreadLocal = ThreadLocal<FileRoute>()

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "files")

        val route = detectRoute(request.url, request.method)
        routeThreadLocal.set(route)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val segments = request.url.pathSegments
        val filesIndex = segments.indexOf("files")

        // Extract file_id from URL path for routes that operate on a specific file
        if (route != FileRoute.CREATE && route != FileRoute.LIST &&
            filesIndex != -1 && segments.size > filesIndex + 1
        ) {
            val fileId = segments[filesIndex + 1]
            if (fileId.isNotBlank()) {
                span.setAttribute("gen_ai.request.file.id", fileId)
            }
        }

        if (route == FileRoute.CREATE) {
            val body = request.body.asFormData() ?: return
            for (part in body.parts) {
                val partName = part.name ?: continue
                when (partName) {
                    "file" -> {
                        span.setAttribute("gen_ai.request.file.size_bytes", part.content.size.toLong())
                        val filename = part.filename
                        if (filename != null) {
                            span.setAttribute("gen_ai.request.file.filename", filename)
                        }
                    }
                    "purpose" -> {
                        val charset = part.contentType?.charset() ?: Charsets.UTF_8
                        span.setAttribute("gen_ai.request.file.purpose", part.content.toString(charset))
                    }
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = routeThreadLocal.get() ?: detectRoute(response.url, response.requestMethod)
        routeThreadLocal.remove()

        val body = response.body.asJson()?.jsonObject ?: return

        when (route) {
            FileRoute.LIST -> {
                // Response: { "data": [...], "has_more": ..., "first_id": ..., "last_id": ... }
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("gen_ai.response.file.count", data.size.toLong())
                }
            }

            FileRoute.DELETE -> {
                // Response: { "id": "file_...", "deleted": true, "type": "file_deleted" }
                body["id"]?.jsonPrimitive?.content?.let { id ->
                    span.setAttribute(GEN_AI_RESPONSE_ID, id)
                    span.setAttribute("gen_ai.response.file.id", id)
                }
                body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
                    span.setAttribute("gen_ai.response.file.deleted", it)
                }
            }

            FileRoute.CONTENT -> {
                // Response body is binary file content; no JSON attributes to extract
            }

            else -> {
                // FileRoute.CREATE, FileRoute.RETRIEVE
                // Response: file object { "id": "...", "type": "file", "filename": "...",
                //   "mime_type": "...", "size_bytes": ..., "created_at": "...", "downloadable": ... }
                body["id"]?.jsonPrimitive?.content?.let { id ->
                    span.setAttribute(GEN_AI_RESPONSE_ID, id)
                    span.setAttribute("gen_ai.response.file.id", id)
                }
                body["filename"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.file.filename", it)
                }
                body["mime_type"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.file.mime_type", it)
                }
                body["size_bytes"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("gen_ai.response.file.size_bytes", it)
                }
                body["created_at"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.file.created_at", it)
                }
                body["downloadable"]?.jsonPrimitive?.booleanOrNull?.let {
                    span.setAttribute("gen_ai.response.file.downloadable", it)
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Files API does not use SSE streaming
    }

    /**
     * Detects which specific Files API route is being called based on the URL path and HTTP method.
     *
     * URL patterns:
     * - `POST   .../files`                   → CREATE
     * - `GET    .../files`                   → LIST
     * - `GET    .../files/{file_id}`         → RETRIEVE
     * - `DELETE .../files/{file_id}`         → DELETE
     * - `GET    .../files/{file_id}/content` → CONTENT
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): FileRoute {
        val segments = url.pathSegments
        val filesIndex = segments.indexOf("files")

        if (filesIndex == -1) {
            logger.warn { "No 'files' segment in URL path: ${segments.joinToString("/")}" }
            return FileRoute.CREATE
        }

        val afterFiles = segments.drop(filesIndex + 1).filter { it.isNotBlank() }
        val hasFileId = afterFiles.isNotEmpty() && afterFiles.first() != "content"
        val hasContent = afterFiles.contains("content")

        return when {
            method == "POST" && !hasFileId && !hasContent -> FileRoute.CREATE
            method == "GET" && !hasFileId && !hasContent -> FileRoute.LIST
            method == "GET" && hasFileId && hasContent -> FileRoute.CONTENT
            method == "GET" && hasFileId -> FileRoute.RETRIEVE
            method == "DELETE" && hasFileId -> FileRoute.DELETE
            else -> {
                logger.warn { "Unknown files operation: $method /${segments.joinToString("/")}" }
                FileRoute.CREATE
            }
        }
    }

    private enum class FileRoute(val operationName: String) {
        CREATE("files.create"),
        LIST("files.list"),
        RETRIEVE("files.retrieve"),
        DELETE("files.delete"),
        CONTENT("files.content"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
