/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
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
 * Handler for the OpenAI Files API (`/v1/files/...`).
 *
 * Routes five Files endpoints based on URL shape and HTTP method, setting
 * `openai.api.type = "files"` and the appropriate `gen_ai.operation.name` on every span.
 *
 * Supported routes:
 * 1. `POST /files` → `files.create` — multipart upload; extracts `tracy.request.purpose`,
 *    `tracy.request.file.filename`, `tracy.request.file.size_bytes`,
 *    `tracy.request.expires_after.anchor`, `tracy.request.expires_after.seconds`;
 *    response: `tracy.response.file.id`, `tracy.response.file.created_at`, `tracy.response.file.expires_at`
 * 2. `GET /files/{id}` → `files.retrieve` — response: same file fields as create
 * 3. `DELETE /files/{id}` → `files.delete` — response: `tracy.response.file.id`, `tracy.response.deleted`
 * 4. `GET /files` → `files.list`
 * 5. `GET /files/{id}/content` → `files.content`
 *
 * See [OpenAI Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = FilesRoute.detect(request.url, request.method)
        span.setAttribute("openai.api.type", "files")
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        if (route == FilesRoute.CREATE) {
            val body = request.body.asFormData() ?: return
            for (part in body.parts) {
                when (part.name) {
                    "file" -> {
                        part.filename?.let { span.setAttribute("tracy.request.file.filename", it) }
                        span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                    }
                    "purpose" ->
                        span.setAttribute("tracy.request.purpose", part.content.toString(Charsets.UTF_8))
                    "expires_after[anchor]" ->
                        span.setAttribute("tracy.request.expires_after.anchor", part.content.toString(Charsets.UTF_8))
                    "expires_after[seconds]" ->
                        part.content.toString(Charsets.UTF_8).toLongOrNull()
                            ?.let { span.setAttribute("tracy.request.expires_after.seconds", it) }
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = FilesRoute.detect(response.url, response.requestMethod)
        span.setAttribute("openai.api.type", "files")
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val body = response.body.asJson()?.jsonObject ?: return
        when (route) {
            FilesRoute.CREATE, FilesRoute.RETRIEVE -> {
                body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.id", it) }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.created_at", it) }
                body["expires_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.expires_at", it) }
            }
            FilesRoute.DELETE -> {
                body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.id", it) }
                body["deleted"]?.jsonPrimitive?.boolean?.let { span.setAttribute("tracy.response.deleted", it) }
            }
            FilesRoute.LIST, FilesRoute.CONTENT -> Unit
        }
    }

    /** Files API does not use SSE streaming. */
    override fun handleStreaming(span: Span, events: String) = Unit

    private enum class FilesRoute(val operationName: String) {
        CREATE("files.create"),
        RETRIEVE("files.retrieve"),
        DELETE("files.delete"),
        LIST("files.list"),
        CONTENT("files.content");

        companion object {
            fun detect(url: TracyHttpUrl, method: String): FilesRoute {
                val segments = url.pathSegments
                val filesIdx = segments.indexOf("files")
                if (filesIdx == -1) {
                    logger.warn { "No 'files' segment in URL: ${segments.joinToString("/")}" }
                    return LIST
                }
                val hasFileId = segments.size > filesIdx + 1 && segments[filesIdx + 1].isNotBlank()
                val hasContent = segments.contains("content")

                return when {
                    method == "POST" && !hasFileId -> CREATE
                    method == "GET" && hasFileId && hasContent -> CONTENT
                    method == "GET" && hasFileId -> RETRIEVE
                    method == "GET" && !hasFileId -> LIST
                    method == "DELETE" && hasFileId -> DELETE
                    else -> {
                        logger.warn { "Unrecognised Files route: $method ${segments.joinToString("/")}" }
                        LIST
                    }
                }
            }

            private val logger = KotlinLogging.logger {}
        }
    }
}
