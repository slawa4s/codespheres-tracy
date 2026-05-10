/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the OpenAI Files API (`/v1/files`).
 *
 * Covers all five file routes:
 * - `POST /v1/files` → `files.create`
 * - `GET /v1/files` → `files.list`
 * - `GET /v1/files/{id}` → `files.retrieve`
 * - `DELETE /v1/files/{id}` → `files.delete`
 * - `GET /v1/files/{id}/content` → `files.content.retrieve`
 *
 * For every route the handler sets:
 * - `openai.api.type = "files"`
 * - `gen_ai.operation.name` derived from the URL path and HTTP method (overrides the value
 *   written by [org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils.setCommonResponseAttributes]
 *   which reads `body["object"]` and emits the raw string `"file"`)
 *
 * For `files.create` the request multipart body is also parsed:
 * - `purpose` field → `tracy.request.purpose`
 * - file part byte count → `tracy.request.file.size_bytes`
 *
 * For all routes the response `id` field is extracted:
 * - `body["id"]` → `tracy.response.file.id`
 *
 * See [OpenAI Files API](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    private enum class FileRoute(val operationName: String) {
        CREATE("files.create"),
        LIST("files.list"),
        RETRIEVE("files.retrieve"),
        DELETE("files.delete"),
        CONTENT_RETRIEVE("files.content.retrieve"),
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        span.setAttribute("openai.api.type", "files")
        span.setAttribute("gen_ai.operation.name", route.operationName)

        if (route == FileRoute.CREATE) {
            val formData = request.body.asFormData() ?: return
            for (part in formData.parts) {
                val charset = part.contentType?.charset() ?: Charsets.UTF_8
                when (part.name) {
                    "purpose" -> span.setAttribute("tracy.request.purpose", part.content.toString(charset))
                    "file" -> span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        // Override gen_ai.operation.name that setCommonResponseAttributes may have set via body["object"] = "file"
        span.setAttribute("gen_ai.operation.name", route.operationName)

        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.id", it) }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.debug { "Files API does not use server-sent events streaming" }
    }

    private fun detectRoute(url: TracyHttpUrl, method: String): FileRoute {
        val segments = url.pathSegments
        val filesIndex = segments.indexOf("files")

        if (filesIndex == -1) {
            logger.warn { "No 'files' segment in path: ${segments.joinToString("/")}" }
            return FileRoute.CREATE
        }

        val after = segments.drop(filesIndex + 1)
        val hasFileId = after.isNotEmpty() && after[0].isNotBlank()
        val hasContentSegment = hasFileId && after.size > 1 && after[1] == "content"

        return when {
            method == "POST"   && !hasFileId                          -> FileRoute.CREATE
            method == "GET"    && !hasFileId                          -> FileRoute.LIST
            method == "GET"    && hasFileId && !hasContentSegment     -> FileRoute.RETRIEVE
            method == "DELETE" && hasFileId                           -> FileRoute.DELETE
            method == "GET"    && hasFileId && hasContentSegment      -> FileRoute.CONTENT_RETRIEVE
            else -> {
                logger.warn { "Unrecognised file route: $method ${segments.joinToString("/")}" }
                FileRoute.CREATE
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
