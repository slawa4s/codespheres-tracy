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
 * Handler for OpenAI Files API.
 *
 * The Files API provides endpoints to upload and manage files for use with
 * Assistants, fine-tuning, batch processing, etc.:
 * 1. `POST /files`           - Upload a file (multipart/form-data)
 * 2. `DELETE /files/{file_id}` - Delete a file
 *
 * This handler detects create vs delete from the HTTP method and traces accordingly.
 *
 * See [Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "files")

        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        when (route) {
            FileRoute.CREATE -> handleCreateRequestAttributes(span, request)
            FileRoute.DELETE -> {
                // DELETE /files/{file_id} — no request body attributes to extract
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute("openai.api.type", "files")

        val route = detectRoute(response.url, response.requestMethod)
        // Override gen_ai.operation.name with the verb-form name
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val body = response.body.asJson()?.jsonObject ?: return

        when (route) {
            FileRoute.CREATE -> {
                body["id"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.response.file.id", it)
                }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.response.file.created_at", it)
                }
                body["expires_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.response.file.expires_at", it)
                }
            }

            FileRoute.DELETE -> {
                body["id"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.response.file.id", it)
                }
                body["deleted"]?.jsonPrimitive?.boolean?.let {
                    span.setAttribute("tracy.response.deleted", it)
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Files API does not support SSE streaming
    }

    private fun handleCreateRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asFormData() ?: return

        for (part in body.parts) {
            val charset = part.contentType?.charset() ?: Charsets.UTF_8
            val partName = part.name ?: continue

            when (partName) {
                "file" -> {
                    span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                    part.filename?.let { span.setAttribute("tracy.request.file.filename", it) }
                }

                "purpose" -> {
                    val content = part.content.toString(charset)
                    span.setAttribute("tracy.request.purpose", content)
                }

                "expires_after" -> {
                    // The SDK submits expires_after as a JSON-encoded form field
                    val content = part.content.toString(charset)
                    try {
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(content).jsonObject
                        json["anchor"]?.jsonPrimitive?.content?.let {
                            span.setAttribute("tracy.request.expires_after.anchor", it)
                        }
                        json["seconds"]?.jsonPrimitive?.longOrNull?.let {
                            span.setAttribute("tracy.request.expires_after.seconds", it)
                        }
                    } catch (e: Exception) {
                        logger.trace { "Failed to parse expires_after form field as JSON: $content" }
                    }
                }

                else -> {
                    logger.trace { "Unhandled files form part: '$partName'" }
                }
            }
        }
    }

    /**
     * Detects the Files API route from the HTTP method.
     *
     * URL patterns:
     * - `POST /files`             → CREATE
     * - `DELETE /files/{file_id}` → DELETE
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): FileRoute {
        return when (method.uppercase()) {
            "POST" -> FileRoute.CREATE
            "DELETE" -> FileRoute.DELETE
            else -> {
                logger.warn { "Unexpected HTTP method '$method' for Files API: ${url.pathSegments.joinToString("/")}" }
                FileRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between Files API routes.
     */
    private enum class FileRoute(val operationName: String) {
        CREATE("files.create"),
        DELETE("files.delete"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
