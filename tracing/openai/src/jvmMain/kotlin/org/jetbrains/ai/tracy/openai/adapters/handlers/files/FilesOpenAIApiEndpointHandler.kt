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
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.FileRouteHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes.GetFileContentHandler

/**
 * Handler for OpenAI Files API.
 *
 * Currently handles:
 * - `GET /v1/files/{file_id}/content` - Download file content (binary response)
 *
 * See [Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    private val routeHandlers: Map<FileRoute, FileRouteHandler> by lazy {
        mapOf(
            FileRoute.FILE_CONTENT to GetFileContentHandler()
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
        logger.warn { "Files API does not use server-sent events streaming" }
    }

    private fun detectRoute(url: TracyHttpUrl, method: String): FileRoute {
        val segments = url.pathSegments
        return when {
            method == "GET" && segments.contains("content") -> FileRoute.FILE_CONTENT
            else -> {
                logger.warn { "Unknown Files API route: $method ${segments.joinToString("/")}" }
                FileRoute.FILE_CONTENT
            }
        }
    }

    private enum class FileRoute {
        FILE_CONTENT  // GET /v1/files/{file_id}/content
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
