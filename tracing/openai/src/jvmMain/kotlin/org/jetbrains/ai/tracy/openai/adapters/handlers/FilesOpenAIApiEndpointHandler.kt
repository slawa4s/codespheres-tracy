/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Files API (create, retrieve, delete, list, content).
 *
 * See [Files API](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operationName = resolveFilesOperation(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
        span.setAttribute("openai.api.type", "files")

        when (operationName) {
            "files.create" -> handleCreateRequest(span, request)
            "files.list" -> handleListRequest(span, request)
            "files.content" -> {
                // Extract file_id from URL path for content
                val fileId = extractFileId(request.url)
                fileId?.let { span.setAttribute("tracy.request.file.id", it) }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val objectType = body["object"]?.jsonPrimitive?.contentOrNull

        when (objectType) {
            "file" -> {
                body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.file.id", it) }
                body["filename"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.file.filename", it) }
                body["bytes"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.size_bytes", it) }
                body["created_at"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.file.created_at", it) }
                body["expires_at"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.file.expires_at", it) }
                body["status"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.file.status", it) }
                body["purpose"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.file.purpose", it) }
                body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.deleted", it) }
            }
            "list" -> {
                val data = body["data"] as? JsonArray
                span.setAttribute("tracy.response.list.count", (data?.size ?: 0).toLong())
                body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.has_more", it) }
            }
            else -> {
                // Delete response: {"id": "...", "deleted": true}
                body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.deleted", it) }
                body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.file.id", it) }
            }
        }

        // File content response (binary) — capture size
        if (objectType == null && response.requestMethod == "GET") {
            response.contentLength?.let { span.setAttribute("tracy.response.file.size_bytes", it) }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private fun handleCreateRequest(span: Span, request: TracyHttpRequest) {
        val formData = request.body.asFormData() ?: return
        for (part in formData.parts) {
            val charset = part.contentType?.charset() ?: Charsets.UTF_8
            when (part.name) {
                "purpose" -> {
                    val purpose = part.content.toString(charset)
                    span.setAttribute("tracy.request.purpose", purpose)
                    span.setAttribute("tracy.request.file.purpose", purpose)
                }
                "file" -> {
                    span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                    part.filename?.let {
                        span.setAttribute("tracy.request.file.filename", it)
                        span.setAttribute("tracy.request.file.name", it)
                    }
                }
                "expires_after" -> {
                    // May be JSON-encoded: {"anchor": "created_at", "seconds": 3600}
                    val text = part.content.toString(charset)
                    runCatching {
                        val obj = Json.parseToJsonElement(text).jsonObject
                        obj["anchor"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.expires_after.anchor", it) }
                        obj["seconds"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.request.expires_after.seconds", it) }
                    }
                }
                "expires_after[anchor]" -> span.setAttribute("tracy.request.expires_after.anchor", part.content.toString(charset))
                "expires_after[seconds]" -> part.content.toString(charset).toLongOrNull()?.let { span.setAttribute("tracy.request.expires_after.seconds", it) }
            }
        }
    }

    private fun handleListRequest(span: Span, request: TracyHttpRequest) {
        val params = request.url.parameters
        params.queryParameter("purpose")?.let { span.setAttribute("tracy.request.purpose", it) }
        params.queryParameter("limit")?.toLongOrNull()?.let { span.setAttribute("tracy.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
        params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
    }

    private fun resolveFilesOperation(url: TracyHttpUrl, method: String): String {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val filesIndex = segments.indexOf("files")
        val hasFileId = filesIndex >= 0 && segments.size > filesIndex + 1
        val hasContent = segments.contains("content")
        return when {
            method == "POST" && !hasFileId -> "files.create"
            method == "GET" && hasContent -> "files.content"
            method == "GET" && hasFileId -> "files.retrieve"
            method == "DELETE" -> "files.delete"
            else -> "files.list"
        }
    }

    private fun extractFileId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val filesIndex = segments.indexOf("files")
        return if (filesIndex >= 0 && segments.size > filesIndex + 1) {
            segments[filesIndex + 1]
        } else null
    }
}
