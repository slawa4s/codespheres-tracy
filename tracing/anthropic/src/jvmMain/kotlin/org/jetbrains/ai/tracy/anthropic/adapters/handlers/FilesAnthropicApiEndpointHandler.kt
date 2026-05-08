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
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic Files API.
 *
 * Maps HTTP method + URL path to `gen_ai.operation.name`:
 * - `POST /v1/files`                  → `"files.create"`
 * - `GET  /v1/files`                  → `"files.list"`
 * - `GET  /v1/files/{file_id}`        → `"files.retrieve"`
 * - `DELETE /v1/files/{file_id}`      → `"files.delete"`
 * - `GET  /v1/files/{file_id}/content`→ `"files.content"`
 *
 * For the LIST route, the following response attributes are populated:
 * - `gen_ai.response.list.count`    → number of entries in the `data` array
 * - `gen_ai.response.list.has_more` → pagination indicator as string ("true"/"false")
 * - `gen_ai.response.list.first_id` → id of the first file in the current page
 * - `gen_ai.response.list.last_id`  → id of the last file in the current page
 *
 * See: [Files API](https://docs.anthropic.com/en/api/files)
 */
internal class FilesAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "files")

        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val segments = request.url.pathSegments
        val filesIndex = segments.indexOf("files")

        // Extract file_id from URL path for routes that have one
        if (route != FileRoute.CREATE && route != FileRoute.LIST &&
            filesIndex != -1 && segments.size > filesIndex + 1
        ) {
            val fileId = segments[filesIndex + 1]
            if (fileId.isNotBlank()) {
                span.setAttribute("gen_ai.request.file.id", fileId)
            }
        }

        if (route == FileRoute.LIST) {
            val params = request.url.parameters
            params.queryParameter("purpose")?.let { span.setAttribute("gen_ai.request.list.purpose", it) }
            params.queryParameter("limit")?.let { span.setAttribute("gen_ai.request.list.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("gen_ai.request.list.order", it) }
            params.queryParameter("after")?.let { span.setAttribute("gen_ai.request.list.after", it) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val route = detectRoute(response.url, response.requestMethod)

        when (route) {
            FileRoute.LIST -> {
                // Response: { data: [...], has_more, first_id, last_id }
                body["data"]?.let { data ->
                    if (data is JsonArray) {
                        span.setAttribute("gen_ai.response.list.count", data.size.toLong())
                    }
                }
                body["has_more"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.list.has_more", it)
                }
                body["first_id"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.list.first_id", it)
                }
                body["last_id"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.list.last_id", it)
                }
            }

            FileRoute.DELETE -> {
                // Response: { id, deleted: true }
                body["id"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.file.id", it)
                }
            }

            FileRoute.CONTENT -> {
                // Response body is binary (file content), no JSON attributes to extract
            }

            else -> {
                // FileRoute.CREATE, FileRoute.RETRIEVE
                // Response: FileObject { id, type, created_at, filename, media_type, purpose, size, status }
                body["id"]?.jsonPrimitive?.content?.let { id ->
                    span.setAttribute(GEN_AI_RESPONSE_ID, id)
                    span.setAttribute("gen_ai.response.file.id", id)
                }
                body["filename"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.file.filename", it)
                }
                body["purpose"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.file.purpose", it)
                }
                body["size"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("gen_ai.response.file.size", it)
                }
                body["created_at"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.file.created_at", it)
                }
                body["status"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.file.status", it)
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
     * - `POST   /v1/files`                  → CREATE
     * - `GET    /v1/files`                  → LIST
     * - `GET    /v1/files/{file_id}`        → RETRIEVE
     * - `DELETE /v1/files/{file_id}`        → DELETE
     * - `GET    /v1/files/{file_id}/content`→ CONTENT
     */
    internal fun detectRoute(url: TracyHttpUrl, method: String): FileRoute {
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
            method == "POST" && !hasFileId -> FileRoute.CREATE
            method == "GET" && !hasFileId -> FileRoute.LIST
            method == "GET" && hasFileId && hasContent -> FileRoute.CONTENT
            method == "GET" && hasFileId -> FileRoute.RETRIEVE
            method == "DELETE" && hasFileId -> FileRoute.DELETE
            else -> {
                logger.warn { "Unknown files route: $method /${segments.joinToString("/")}" }
                FileRoute.CREATE
            }
        }
    }

    internal enum class FileRoute(val operationName: String) {
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
