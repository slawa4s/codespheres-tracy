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
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.DeleteFileHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.FileRouteHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.ListFilesHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.RetrieveFileContentHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.RetrieveFileHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.UploadFileHandler

/**
 * Handler for the OpenAI Files API.
 *
 * The Files API provides endpoints for managing uploaded files:
 * 1. `POST /files` - Upload a file
 * 2. `GET /files` - List files
 * 3. `GET /files/{file_id}` - Retrieve file metadata
 * 4. `DELETE /files/{file_id}` - Delete a file
 * 5. `GET /files/{file_id}/content` - Retrieve file content (binary)
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
            FileRoute.UPLOAD to UploadFileHandler(),
            FileRoute.LIST to ListFilesHandler(),
            FileRoute.RETRIEVE to RetrieveFileHandler(),
            FileRoute.DELETE to DeleteFileHandler(),
            FileRoute.RETRIEVE_CONTENT to RetrieveFileContentHandler(),
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
            logger.warn { "Failed to detect file route. Endpoint has no `files` path segment: ${segments.joinToString(separator = "/")}" }
            return FileRoute.LIST
        }

        val hasFileId = segments.size > filesIndex + 1 &&
                segments[filesIndex + 1].isNotBlank()
        val hasContentSegment = segments.contains("content")

        return when {
            method == "POST" && !hasFileId -> FileRoute.UPLOAD
            method == "GET" && !hasFileId -> FileRoute.LIST
            method == "GET" && hasFileId && hasContentSegment -> FileRoute.RETRIEVE_CONTENT
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
        UPLOAD("files.upload"),                   // POST /files
        LIST("files.list"),                       // GET /files
        RETRIEVE("files.retrieve"),               // GET /files/{file_id}
        DELETE("files.delete"),                   // DELETE /files/{file_id}
        RETRIEVE_CONTENT("files.retrieve_content"), // GET /files/{file_id}/content
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
