/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
 * Handler for OpenAI Files API.
 *
 * The Files API provides endpoints to upload and manage files used across various OpenAI services:
 * 1. `POST /files` - Upload a file (multipart form data)
 * 2. `GET /files` - List all files
 * 3. `GET /files/{file_id}` - Retrieve a file's metadata
 * 4. `DELETE /files/{file_id}` - Delete a file
 * 5. `GET /files/{file_id}/content` - Retrieve the file content (binary)
 *
 * This handler detects the specific route from the URL and HTTP method, stores the
 * resolved operation name in a [ThreadLocal] during the request phase, and re-applies
 * it in the response phase to override what [OpenAIApiUtils.setCommonResponseAttributes]
 * sets from the response `object` field (e.g. `"file"`, `"list"`, `"file.deleted"`).
 *
 * See [Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    /**
     * Stores the operation name detected during request handling so it can be
     * re-applied in [handleResponseAttributes] after [setCommonResponseAttributes]
     * overwrites [GEN_AI_OPERATION_NAME] with the raw JSON `object` field.
     */
    private val operationNameThreadLocal = ThreadLocal<String>()

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "files")

        val route = detectRoute(request.url, request.method)
        operationNameThreadLocal.set(route.operationName)

        val segments = request.url.pathSegments
        val filesIndex = segments.indexOf("files")

        // Extract file_id from URL path for non-CREATE and non-LIST routes
        if (route != FileRoute.CREATE && route != FileRoute.LIST &&
            filesIndex != -1 && segments.size > filesIndex + 1
        ) {
            val fileId = segments[filesIndex + 1]
            if (fileId.isNotBlank()) {
                span.setAttribute("tracy.file.id", fileId)
            }
        }

        when (route) {
            FileRoute.CREATE -> {
                // POST /files uses multipart form data: fields are `file` and `purpose`
                val body = request.body.asFormData() ?: return
                for (part in body.parts) {
                    val partName = part.name ?: continue
                    val charset = part.contentType?.charset() ?: Charsets.UTF_8
                    when (partName) {
                        "file" -> {
                            span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                            val filename = part.filename
                            if (filename != null) {
                                span.setAttribute("tracy.request.file.filename", filename)
                            }
                        }
                        "purpose" -> {
                            span.setAttribute("tracy.request.file.purpose", part.content.toString(charset))
                        }
                    }
                }
            }

            FileRoute.LIST -> {
                // GET /files accepts query parameters for filtering and pagination
                val params = request.url.parameters
                params.queryParameter("purpose")?.let { span.setAttribute("tracy.request.purpose", it) }
                params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
                params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
                params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
            }

            else -> {
                // RETRIEVE, DELETE, CONTENT: file_id is already extracted from URL above
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // Re-apply the correct operation name, overriding what setCommonResponseAttributes
        // set from the JSON `object` field (e.g. "file", "list", "file.deleted").
        span.setAttribute(GEN_AI_OPERATION_NAME, operationNameThreadLocal.get() ?: FileRoute.CREATE.operationName)
        operationNameThreadLocal.remove()

        val body = response.body.asJson()?.jsonObject ?: return
        val route = detectRoute(response.url, response.requestMethod)

        when (route) {
            FileRoute.LIST -> {
                // Response: { object: "list", data: [...], first_id, last_id, has_more }
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("tracy.file.count", data.size.toLong())
                }
            }

            FileRoute.DELETE -> {
                // Response: FileDeleted { id, deleted, object: "file.deleted" }
                body["id"]?.let { span.setAttribute("tracy.file.id", it.jsonPrimitive.content) }
                body["deleted"]?.let {
                    span.setAttribute("tracy.file.deleted", it.jsonPrimitive.boolean)
                }
            }

            FileRoute.CONTENT -> {
                // Response body is binary (file content), no JSON attributes to extract
            }

            else -> {
                // FileRoute.CREATE, FileRoute.RETRIEVE
                // Response: FileObject { id, bytes, created_at, filename, object, purpose, status }
                body["id"]?.let {
                    val id = it.jsonPrimitive.content
                    span.setAttribute("tracy.file.id", id)
                    span.setAttribute(GEN_AI_RESPONSE_ID, id)
                }
                body["filename"]?.let { span.setAttribute("tracy.file.filename", it.jsonPrimitive.content) }
                body["purpose"]?.let { span.setAttribute("tracy.file.purpose", it.jsonPrimitive.content) }
                body["bytes"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.file.bytes", it) }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.file.created_at", it)
                }
                body["status"]?.let { span.setAttribute("tracy.file.status", it.jsonPrimitive.content) }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Files API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific Files API endpoint is being called based on the URL path and HTTP method.
     *
     * URL patterns:
     * - `POST /files`                  → CREATE
     * - `GET  /files`                  → LIST
     * - `GET  /files/{file_id}`        → RETRIEVE
     * - `DELETE /files/{file_id}`      → DELETE
     * - `GET  /files/{file_id}/content`→ CONTENT
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): FileRoute {
        val segments = url.pathSegments
        val filesIndex = segments.indexOf("files")

        if (filesIndex == -1) {
            logger.warn { "Failed to detect files route. Endpoint has no `files` path segment: ${url.pathSegments.joinToString(separator = "/")}" }
            return FileRoute.CREATE
        }

        val hasFileId = segments.size > (filesIndex + 1) &&
                segments[filesIndex + 1].isNotBlank()
        val hasContent = segments.contains("content")

        return when {
            method == "POST" && !hasFileId -> FileRoute.CREATE
            method == "GET" && !hasFileId -> FileRoute.LIST
            method == "GET" && hasFileId && hasContent -> FileRoute.CONTENT
            method == "GET" && hasFileId -> FileRoute.RETRIEVE
            method == "DELETE" && hasFileId -> FileRoute.DELETE
            else -> {
                logger.warn { "Failed to detect files route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                FileRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different Files API routes.
     */
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
