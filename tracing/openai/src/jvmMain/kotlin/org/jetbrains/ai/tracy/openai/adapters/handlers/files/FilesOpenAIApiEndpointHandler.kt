/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import io.opentelemetry.api.trace.Span
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.*

/**
 * Handler for OpenAI Files API.
 *
 * The Files API provides multiple endpoints for file operations:
 * 1. `POST /files` - Upload a file (`files.create`)
 * 2. `GET /files/{file_id}` - Retrieve file metadata (`files.retrieve`)
 * 3. `GET /files` - List all files (`files.list`)
 * 4. `DELETE /files/{file_id}` - Delete a file (`files.delete`)
 *
 * This handler detects the specific route and traces accordingly.
 *
 * See [Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    private val routeHandlers: Map<FileRoute, FileRouteHandler> by lazy {
        mapOf(
            FileRoute.CREATE to CreateFileHandler(),
            FileRoute.RETRIEVE to RetrieveFileHandler(),
            FileRoute.LIST to ListFilesHandler(),
            FileRoute.DELETE to DeleteFileHandler(),
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
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
            logger.warn { "Failed to detect file route. Endpoint has no `files` path segment: ${url.pathSegments.joinToString(separator = "/")}" }
            return FileRoute.LIST
        }

        val containsFileId = segments.size > (filesIndex + 1) &&
                segments[filesIndex + 1].isNotBlank()

        return when {
            method == "POST" && !containsFileId -> FileRoute.CREATE
            method == "GET" && containsFileId -> FileRoute.RETRIEVE
            method == "GET" && !containsFileId -> FileRoute.LIST
            method == "DELETE" && containsFileId -> FileRoute.DELETE
            else -> {
                logger.warn { "Failed to detect file route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                FileRoute.LIST
            }
        }
    }

    private enum class FileRoute {
        CREATE,   // POST /files
        RETRIEVE, // GET /files/{file_id}
        LIST,     // GET /files
        DELETE,   // DELETE /files/{file_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
