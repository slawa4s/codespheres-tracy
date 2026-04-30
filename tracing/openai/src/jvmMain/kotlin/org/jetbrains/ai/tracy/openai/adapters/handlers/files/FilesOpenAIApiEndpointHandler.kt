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

/**
 * Handler for OpenAI Files API.
 *
 * The Files API provides endpoints for managing uploaded files:
 * 1. `POST /v1/files` - Upload a file
 * 2. `GET /v1/files/{id}` - Retrieve file metadata
 * 3. `GET /v1/files/{id}/content` - Retrieve file content
 * 4. `GET /v1/files` - List files
 * 5. `DELETE /v1/files/{id}` - Delete a file
 *
 * This handler detects the specific route and traces accordingly, setting
 * `gen_ai.operation.name` and `openai.api.type` on each span.
 *
 * See [Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setNetworkRequestAttributes(span, request)
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        span.setAttribute("openai.api.type", "files")
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setHttpStatusCode(span, response)
        // Override gen_ai.operation.name that may have been set incorrectly by setCommonResponseAttributes
        val route = detectRoute(response.url, response.requestMethod)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
    }

    override fun handleStreaming(span: Span, events: String) {
        // Files API does not use server-sent events streaming
        logger.warn { "Files API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific files endpoint is being called based on the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): FileRoute {
        val segments = url.pathSegments
        val filesIndex = segments.indexOf("files")
        if (filesIndex == -1) {
            logger.warn { "Failed to detect files route. Endpoint has no `files` path segment: ${segments.joinToString(separator = "/")}" }
            return FileRoute.LIST
        }

        val hasFileId = segments.size > filesIndex + 1 && segments[filesIndex + 1].isNotBlank()
        val hasContentSegment = hasFileId &&
                segments.size > filesIndex + 2 &&
                segments[filesIndex + 2] == "content"

        return when {
            method == "POST" && !hasFileId -> FileRoute.CREATE
            method == "GET" && hasFileId && hasContentSegment -> FileRoute.CONTENT
            method == "GET" && hasFileId && !hasContentSegment -> FileRoute.RETRIEVE
            method == "GET" && !hasFileId -> FileRoute.LIST
            method == "DELETE" && hasFileId -> FileRoute.DELETE
            else -> {
                logger.warn { "Failed to detect files route: $method ${segments.joinToString(separator = "/")}" }
                FileRoute.LIST
            }
        }
    }

    /**
     * Internal enum to distinguish between different files API routes.
     */
    internal enum class FileRoute(val operationName: String) {
        CREATE("files.create"),    // POST /v1/files
        RETRIEVE("files.retrieve"), // GET /v1/files/{id}
        CONTENT("files.content"),  // GET /v1/files/{id}/content
        LIST("files.list"),        // GET /v1/files
        DELETE("files.delete"),    // DELETE /v1/files/{id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
