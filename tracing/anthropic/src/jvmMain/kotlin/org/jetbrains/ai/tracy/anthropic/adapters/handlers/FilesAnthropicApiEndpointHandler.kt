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
 * - `POST   /v1/files`                    → `"files.create"`
 * - `GET    /v1/files`                    → `"files.list"`
 * - `GET    /v1/files/{file_id}`          → `"files.retrieve"`
 * - `DELETE /v1/files/{file_id}`          → `"files.delete"`
 * - `GET    /v1/files/{file_id}/content`  → `"files.content"`
 *
 * Request attributes (CREATE only, from multipart form-data `file` part):
 * - `gen_ai.request.file.filename`   → filename from the `file` part
 * - `gen_ai.request.file.size_bytes` → byte count of the uploaded content
 * - `gen_ai.request.file.mime_type`  → MIME type of the uploaded file
 *
 * Response attributes (CREATE/RETRIEVE):
 * - `gen_ai.response.id`                → file `id` (also set as `gen_ai.response.file.id`)
 * - `gen_ai.response.file.id`           → unique file id
 * - `gen_ai.response.file.filename`     → filename stored on the server
 * - `gen_ai.response.file.mime_type`    → MIME type of the stored file
 * - `gen_ai.response.file.size_bytes`   → size in bytes (Long)
 * - `gen_ai.response.file.downloadable` → whether the file can be downloaded (boolean string)
 * - `gen_ai.response.file.created_at`   → ISO-8601 creation timestamp
 *
 * Response attributes (LIST):
 * - `gen_ai.response.list.count`    → number of files returned in this page
 * - `gen_ai.response.list.has_more` → whether additional pages exist (boolean string)
 * - `gen_ai.response.list.first_id` → id of the first file in the page
 * - `gen_ai.response.list.last_id`  → id of the last file in the page
 *
 * Response attributes (DELETE):
 * - `gen_ai.response.id`        → id of the deleted file
 * - `gen_ai.response.file.id`   → id of the deleted file
 *
 * See: [Files API](https://docs.anthropic.com/en/api/files)
 */
internal class FilesAnthropicApiEndpointHandler : EndpointApiHandler {

    /**
     * Stores the detected [FileRoute] during [handleRequestAttributes] so that
     * [handleResponseAttributes] can reuse it without re-parsing the URL.
     *
     * A [ThreadLocal] is used to avoid shared mutable state between concurrent requests.
     */
    private val routeThreadLocal = ThreadLocal<FileRoute>()

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "files")

        val route = detectRoute(request.url, request.method)
        routeThreadLocal.set(route)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        if (route == FileRoute.CREATE) {
            val formData = request.body.asFormData() ?: return
            for (part in formData.parts) {
                if (part.name == "file") {
                    span.setAttribute("gen_ai.request.file.size_bytes", part.content.size.toLong())
                    part.filename?.let { span.setAttribute("gen_ai.request.file.filename", it) }
                    part.contentType?.let { ct ->
                        span.setAttribute("gen_ai.request.file.mime_type", "${ct.type}/${ct.subtype}")
                    }
                    break
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = routeThreadLocal.get() ?: detectRoute(response.url, response.requestMethod)
        routeThreadLocal.remove()

        val body = response.body.asJson()?.jsonObject ?: return

        when (route) {
            FileRoute.CREATE, FileRoute.RETRIEVE -> {
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
                body["downloadable"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.file.downloadable", it)
                }
                body["created_at"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.response.file.created_at", it)
                }
            }

            FileRoute.LIST -> {
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
                body["id"]?.jsonPrimitive?.content?.let { id ->
                    span.setAttribute(GEN_AI_RESPONSE_ID, id)
                    span.setAttribute("gen_ai.response.file.id", id)
                }
            }

            FileRoute.CONTENT -> {
                // Response body is binary file content; no JSON attributes to extract
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Files API does not use SSE streaming
    }

    /**
     * Detects which Files API route is being called based on the URL path and HTTP method.
     *
     * URL patterns:
     * - `POST   .../files`                   → [FileRoute.CREATE]
     * - `GET    .../files`                   → [FileRoute.LIST]
     * - `GET    .../files/{file_id}`         → [FileRoute.RETRIEVE]
     * - `DELETE .../files/{file_id}`         → [FileRoute.DELETE]
     * - `GET    .../files/{file_id}/content` → [FileRoute.CONTENT]
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
