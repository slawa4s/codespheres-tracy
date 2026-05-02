/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Files API endpoints:
 * - POST /v1/files — Upload a file
 * - GET /v1/files — List files
 * - GET /v1/files/{file_id} — Retrieve file metadata
 * - DELETE /v1/files/{file_id} — Delete a file
 * - GET /v1/files/{file_id}/content — Retrieve file content
 *
 * Sets [GEN_AI_OPERATION_NAME] from the HTTP method and URL structure before
 * [org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils.setCommonResponseAttributes]
 * runs, because the raw `"object"` field value returned by the API (`"file"`) does not match
 * the expected operation name values (`"files.create"`, `"files.list"`, etc.).
 *
 * For `multipart/form-data` upload requests, extracts:
 * - `tracy.request.purpose` from the `purpose` form part
 * - `tracy.request.file.filename` and `tracy.request.file.size_bytes` from the `file` form part
 * - `tracy.request.expires_after.anchor` and `tracy.request.expires_after.seconds` from the
 *   `expires_after` form part (parsed as JSON)
 *
 * Response attributes extracted from the JSON body:
 * - `tracy.response.file.id`, `tracy.response.file.created_at`, `tracy.response.file.expires_at`,
 *   `tracy.response.file.status`, `tracy.response.file.filename`, `tracy.response.file.size_bytes`,
 *   `tracy.response.file.purpose`
 *
 * See [Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operationName = detectOperationName(request.method, request.url.pathSegments)
        operationName?.let { span.setAttribute(GEN_AI_OPERATION_NAME, it) }

        val body = request.body.asFormData() ?: return

        for (part in body.parts) {
            val contentType = part.contentType
            when (part.name) {
                "purpose" -> {
                    val text = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute("tracy.request.purpose", text)
                }
                "file" -> {
                    part.filename?.let { span.setAttribute("tracy.request.file.filename", it) }
                    span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                }
                "expires_after" -> {
                    val text = part.content.toString(contentType?.charset() ?: Charsets.UTF_8)
                    runCatching {
                        val json = Json.parseToJsonElement(text).jsonObject
                        json["anchor"]?.jsonPrimitive?.content?.let {
                            span.setAttribute("tracy.request.expires_after.anchor", it)
                        }
                        json["seconds"]?.jsonPrimitive?.longOrNull?.let {
                            span.setAttribute("tracy.request.expires_after.seconds", it)
                        }
                    }.onFailure {
                        logger.warn { "Failed to parse 'expires_after' form part as JSON: $text" }
                    }
                }
                null -> logger.warn { "Form data part with missing name ignored. Content type: '$contentType'" }
                else -> {
                    // Other parts are not traced.
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.id", it) }
        body["created_at"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.created_at", it) }
        body["expires_at"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.expires_at", it) }
        body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.status", it) }
        body["filename"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.filename", it) }
        body["bytes"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.size_bytes", it) }
        body["purpose"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.purpose", it) }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Files API does not use server-sent events streaming" }
    }

    /**
     * Infers the Files API operation name from the HTTP method and URL path segments.
     *
     * - POST /v1/files → `"files.create"`
     * - DELETE /v1/files/{file_id} → `"files.delete"`
     * - GET /v1/files/{file_id}/content → `"files.content"`
     * - GET /v1/files/{file_id} → `"files.retrieve"`
     * - GET /v1/files → `"files.list"`
     */
    private fun detectOperationName(method: String, pathSegments: List<String>): String? {
        val filesIndex = pathSegments.indexOf("files")
        val hasFileId = filesIndex != -1 &&
                pathSegments.size > filesIndex + 1 &&
                pathSegments[filesIndex + 1].isNotBlank()
        val hasContent = filesIndex != -1 &&
                pathSegments.getOrNull(filesIndex + 2) == "content"

        return when (method.uppercase()) {
            "POST" -> "files.create"
            "DELETE" -> "files.delete"
            "GET" -> when {
                hasFileId && hasContent -> "files.content"
                hasFileId -> "files.retrieve"
                else -> "files.list"
            }
            else -> null
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
