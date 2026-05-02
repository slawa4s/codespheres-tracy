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

/**
 * Handler for OpenAI Files API endpoints:
 * - POST /v1/files
 * - GET /v1/files
 * - GET /v1/files/{file_id}
 * - DELETE /v1/files/{file_id}
 * - GET /v1/files/{file_id}/content
 *
 * Sets [GEN_AI_OPERATION_NAME] based on the HTTP method and URL path.
 *
 * See [Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operationName = detectOperationName(request.url.pathSegments, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // Files API responses are management objects (file metadata or binary content) — no generative attributes to extract.
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Files API does not use server-sent events streaming" }
    }

    private fun detectOperationName(pathSegments: List<String>, method: String): String {
        val filesIndex = pathSegments.indexOf("files")
        val hasFileId = filesIndex != -1 && pathSegments.size > filesIndex + 1 && pathSegments[filesIndex + 1].isNotBlank()
        val hasContent = pathSegments.contains("content")

        return when {
            method == "POST" && !hasFileId -> "files.upload"
            method == "GET" && !hasFileId -> "files.list"
            method == "GET" && hasFileId && hasContent -> "files.content"
            method == "GET" && hasFileId -> "files.retrieve"
            method == "DELETE" && hasFileId -> "files.delete"
            else -> "files"
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
