/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.populateUnmappedAttributes
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Handler for OpenAI Files API (upload, retrieve, list, delete, content).
 * See: https://platform.openai.com/docs/api-reference/files
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val segments = request.url.pathSegments
        val path = segments.joinToString("/")
        val method = request.method.uppercase()
        val filesIdx = segments.indexOf("files")
        val hasId = filesIdx >= 0 && segments.size > filesIdx + 1 && segments[filesIdx + 1].isNotBlank()

        when {
            method == "POST" && !hasId -> handleCreateRequest(span, request)
            method == "GET" && !hasId -> handleListRequest(span, request)
            method == "GET" && path.contains("content") -> handleContentRequest(span, request, segments, filesIdx)
            else -> Unit
        }
    }

    private fun handleCreateRequest(span: Span, request: TracyHttpRequest) {
        val formData = request.body.asFormData() ?: return
        for (part in formData.parts) {
            val charset = part.contentType?.charset() ?: Charsets.UTF_8
            when (part.name) {
                "purpose" -> {
                    val value = part.content.toString(charset)
                    span.setAttribute("tracy.request.purpose", value)
                    span.setAttribute("tracy.request.file.purpose", value)
                }
                "file" -> {
                    span.setAttribute("tracy.request.file.size_bytes", part.content.size.toLong())
                    if (part.filename != null) {
                        span.setAttribute("tracy.request.file.filename", part.filename)
                        span.setAttribute("tracy.request.file.name", part.filename)
                    }
                }
                "expires_after[anchor]" -> {
                    span.setAttribute("tracy.request.expires_after.anchor", part.content.toString(charset))
                }
                "expires_after[seconds]" -> {
                    part.content.toString(charset).toLongOrNull()?.let {
                        span.setAttribute("tracy.request.expires_after.seconds", it)
                    }
                }
            }
        }
    }

    private fun handleListRequest(span: Span, request: TracyHttpRequest) {
        request.url.parameters.queryParameter("purpose")?.let {
            span.setAttribute("tracy.request.purpose", it)
        }
        request.url.parameters.queryParameter("limit")?.toLongOrNull()?.let {
            span.setAttribute("tracy.request.limit", it)
        }
        request.url.parameters.queryParameter("order")?.let {
            span.setAttribute("tracy.request.order", it)
        }
        request.url.parameters.queryParameter("after")?.let {
            span.setAttribute("tracy.request.after", it)
        }
    }

    private fun handleContentRequest(span: Span, request: TracyHttpRequest, segments: List<String>, filesIdx: Int) {
        if (filesIdx >= 0 && segments.size > filesIdx + 1) {
            span.setAttribute("tracy.request.file.id", segments[filesIdx + 1])
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val segments = response.url.pathSegments
        val path = segments.joinToString("/")
        val method = response.requestMethod.uppercase()
        val filesIdx = segments.indexOf("files")
        val hasId = filesIdx >= 0 && segments.size > filesIdx + 1 && segments[filesIdx + 1].isNotBlank()

        when {
            method == "POST" && !hasId -> handleCreateResponse(span, response)
            method == "GET" && !hasId -> handleListResponse(span, response)
            method == "GET" && path.contains("content") -> handleContentResponse(span, response)
            method == "GET" && hasId -> handleRetrieveResponse(span, response)
            method == "DELETE" && hasId -> handleDeleteResponse(span, response)
        }
    }

    private fun handleCreateResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        setFileAttributes(span, body)
        span.populateUnmappedAttributes(body, fileMappedAttributes, PayloadType.RESPONSE)
    }

    private fun handleListResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        (body["data"] as? JsonArray)?.let { data ->
            span.setAttribute("tracy.response.list.count", data.size.toLong())
        }
        body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.response.has_more", it)
        }
        span.populateUnmappedAttributes(body, listOf("data", "has_more", "object", "first_id", "last_id"), PayloadType.RESPONSE)
    }

    private fun handleContentResponse(span: Span, response: TracyHttpResponse) {
        // Binary file content - use injected content length
        val body = response.body.asJson()?.jsonObject ?: return
        body["response_content_length"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.response.file.size_bytes", it)
        }
    }

    private fun handleRetrieveResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        setFileAttributes(span, body)
        span.populateUnmappedAttributes(body, fileMappedAttributes, PayloadType.RESPONSE)
    }

    private fun handleDeleteResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.response.deleted", it)
        }
        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.file.id", it)
        }
        span.populateUnmappedAttributes(body, listOf("deleted", "id", "object"), PayloadType.RESPONSE)
    }

    private fun setFileAttributes(span: Span, body: JsonObject) {
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.id", it) }
        body["filename"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.filename", it) }
        body["bytes"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.size_bytes", it) }
        body["purpose"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.purpose", it) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.created_at", it) }
        body["expires_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.expires_at", it) }
        body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.status", it) }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private val fileMappedAttributes = listOf("id", "filename", "bytes", "purpose", "created_at", "expires_at", "status", "object")
}
