/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.CreateFileHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.DeleteFileHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.FileRouteHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.GetFileContentHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.ListFilesHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.RetrieveFileHandler

/**
 * Handler for the OpenAI Files API.
 *
 * The Files API provides endpoints for managing uploaded files:
 * 1. `POST /v1/files` - Upload a file (`files.create`)
 * 2. `GET /v1/files` - List files (`files.list`)
 * 3. `GET /v1/files/{file_id}` - Retrieve file metadata (`files.retrieve`)
 * 4. `DELETE /v1/files/{file_id}` - Delete a file (`files.delete`)
 * 5. `GET /v1/files/{file_id}/content` - Retrieve file content (binary) (`files.content`)
 *
 * This handler detects the specific route and traces accordingly, setting
 * `gen_ai.operation.name`, `openai.api.type`, and `gen_ai.provider.name` on each span.
 *
 * See [Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    /**
     * Registry of route handlers, initialized lazily to avoid creating handlers until needed.
     */
    private val routeHandlers: Map<FileRoute, FileRouteHandler> by lazy {
        mapOf(
            FileRoute.CREATE to CreateFileHandler(),
            FileRoute.LIST to ListFilesHandler(),
            FileRoute.RETRIEVE to RetrieveFileHandler(),
            FileRoute.DELETE to DeleteFileHandler(),
            FileRoute.CONTENT to GetFileContentHandler(),
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setNetworkRequestAttributes(span, request)
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        span.setAttribute("openai.api.type", "files")
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setHttpStatusCode(span, response)
        // Override gen_ai.operation.name that may have been set incorrectly by setCommonResponseAttributes
        val route = detectRoute(response.url, response.requestMethod)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        routeHandlers[route]?.handleResponse(span, response)
    }

    override fun handleStreaming(span: Span, events: String) {
        // Files API does not use server-sent events streaming
        logger.warn { "Files API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific file endpoint is being called based on the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): FileRoute {
        val segments = url.pathSegments
        val filesIndex = segments.indexOf("files")
        if (filesIndex == -1) {
            logger.warn { "Failed to detect file route. Endpoint has no `files` path segment: ${segments.joinToString(separator = "/")}" }
            return FileRoute.LIST
        }

        val hasFileId = segments.size > filesIndex + 1 &&
                segments[filesIndex + 1].isNotBlank()
        val hasContentSegment = hasFileId &&
                segments.size > filesIndex + 2 &&
                segments[filesIndex + 2] == "content"

        return when {
            method == "POST" && !hasFileId -> FileRoute.CREATE
            method == "GET" && !hasFileId -> FileRoute.LIST
            method == "GET" && hasFileId && hasContentSegment -> FileRoute.CONTENT
            method == "GET" && hasFileId && !hasContentSegment -> FileRoute.RETRIEVE
            method == "DELETE" && hasFileId -> FileRoute.DELETE
            else -> {
                logger.warn { "Failed to detect file route: $method ${segments.joinToString(separator = "/")}" }
                FileRoute.LIST
            }
        }
    }

    /**
     * Internal enum to distinguish between different file API routes.
     */
    internal enum class FileRoute(val operationName: String) {
        CREATE("files.create"),   // POST /v1/files
        LIST("files.list"),       // GET /v1/files
        RETRIEVE("files.retrieve"), // GET /v1/files/{file_id}
        DELETE("files.delete"),   // DELETE /v1/files/{file_id}
        CONTENT("files.content"), // GET /v1/files/{file_id}/content
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
