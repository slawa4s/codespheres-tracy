/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
 * Handler for OpenAI Files API endpoints (`/v1/files`).
 *
 * Derives `gen_ai.operation.name` from the HTTP method:
 * - `POST` → `files.create`
 * - `DELETE` → `files.delete`
 * - `GET` (list) → `files.list`
 * - `GET` (single) → `files.retrieve`
 * - `GET` (content) → `files.content`
 *
 * ## Request attributes extracted
 * - `openai.api.type` — always `"files"`
 * - `gen_ai.operation.name` — derived from HTTP method and URL path
 * - `tracy.request.purpose` — `purpose` multipart field (POST only)
 * - `tracy.request.file.size_bytes` — byte length of the uploaded `file` part (POST only)
 *
 * ## Response attributes extracted
 * - `tracy.response.file.id` — `id` field from the JSON response body
 *
 * See [Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "files")
        span.setAttribute(GEN_AI_OPERATION_NAME, detectOperationName(request))

        if (request.method.uppercase() == "POST") {
            val body = request.body.asFormData() ?: return
            for (part in body.parts) {
                val charset = part.contentType?.charset() ?: Charsets.UTF_8
                when (part.name) {
                    "purpose" -> span.setAttribute("tracy.request.purpose", part.content.toString(charset))
                    "file" -> span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                    null -> logger.warn { "Files form-data part with missing name ignored" }
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        // Re-set operation name because setCommonResponseAttributes overwrites it with body["object"]
        span.setAttribute(GEN_AI_OPERATION_NAME, deriveOperationNameFromResponse(response.url, body))
        body["id"]?.jsonPrimitive?.content?.let { id ->
            span.setAttribute("tracy.response.file.id", id)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Files API does not use server-sent events streaming" }
    }

    private fun deriveOperationNameFromResponse(url: TracyHttpUrl, body: JsonObject): String {
        if (body["deleted"]?.jsonPrimitive?.booleanOrNull == true) return "files.delete"
        return when (body["object"]?.jsonPrimitive?.content) {
            "list" -> "files.list"
            "file" -> when {
                url.pathSegments.contains("content") -> "files.content"
                url.pathSegments.lastOrNull() == "files" -> "files.create"
                else -> "files.retrieve"
            }
            else -> {
                logger.warn { "Could not derive files operation name from response; defaulting to files.retrieve" }
                "files.retrieve"
            }
        }
    }

    private fun detectOperationName(request: TracyHttpRequest): String {
        return when (request.method.uppercase()) {
            "POST" -> "files.create"
            "DELETE" -> "files.delete"
            "GET" -> {
                val segments = request.url.pathSegments
                when {
                    segments.contains("content") -> "files.content"
                    segments.lastOrNull() == "files" -> "files.list"
                    else -> "files.retrieve"
                }
            }
            else -> {
                logger.warn { "Unknown HTTP method for files endpoint: ${request.method}" }
                "files.retrieve"
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
