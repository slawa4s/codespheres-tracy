/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Files API.
 *
 * Handles create, retrieve, list, delete, and content operations on `/v1/files`.
 *
 * See: [Files API](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "files")
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        val filesIdx = segments.indexOf("files")
        val hasFileId = filesIdx >= 0 && segments.size > filesIdx + 1
        val isContent = hasFileId && segments.last() == "content"

        val operation = when {
            isContent -> "files.content"
            hasFileId && request.method == "DELETE" -> "files.delete"
            hasFileId -> "files.retrieve"
            request.method == "GET" -> "files.list"
            else -> "files.create"
        }
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        when (operation) {
            "files.create" -> {
                val form = request.body.asFormData()
                form?.parts?.firstOrNull { it.name == "purpose" }?.let {
                    span.setAttribute("tracy.request.purpose", it.content.toString(Charsets.UTF_8))
                }
                form?.parts?.firstOrNull { it.name == "file" }?.let { filePart ->
                    filePart.filename?.let { span.setAttribute("tracy.request.file.filename", it) }
                    span.setAttribute("tracy.request.file.size_bytes", filePart.content.size.toLong())
                }
                // expires_after may be in the form body
                form?.parts?.firstOrNull { it.name == "expires_after[anchor]" }?.let {
                    span.setAttribute("tracy.request.expires_after.anchor", it.content.toString(Charsets.UTF_8))
                }
                form?.parts?.firstOrNull { it.name == "expires_after[seconds]" }?.let {
                    span.setAttribute("tracy.request.expires_after.seconds", it.content.toString(Charsets.UTF_8).toLongOrNull() ?: 0L)
                }
            }
            "files.list" -> {
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
            "files.content" -> {
                if (hasFileId) {
                    span.setAttribute("tracy.request.file.id", segments[filesIdx + 1])
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonResponseAttributes(span, response)
        span.setAttribute("openai.api.type", "files")

        val segments = response.url.pathSegments.filter { it.isNotEmpty() }
        val filesIdx = segments.indexOf("files")
        val hasFileId = filesIdx >= 0 && segments.size > filesIdx + 1
        val isContent = hasFileId && segments.last() == "content"

        val operation = when {
            isContent -> "files.content"
            hasFileId && response.requestMethod == "DELETE" -> "files.delete"
            hasFileId -> "files.retrieve"
            response.requestMethod == "GET" -> "files.list"
            else -> "files.create"
        }
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        when (operation) {
            "files.list" -> {
                body["data"]?.let { data ->
                    if (data is kotlinx.serialization.json.JsonArray) {
                        span.setAttribute("tracy.response.list.count", data.size.toLong())
                    }
                }
                body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
                    span.setAttribute("tracy.response.has_more", it)
                }
                body["first_id"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.response.first_id", it)
                }
                body["last_id"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.response.last_id", it)
                }
            }
            "files.delete" -> {
                body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.id", it) }
                body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.deleted", it) }
            }
            "files.content" -> {
                // binary response — size comes from content-length
                response.contentLength?.let { span.setAttribute("tracy.response.file.size_bytes", it) }
            }
            else -> {
                // create or retrieve: full file object
                body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.id", it) }
                body["filename"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.filename", it) }
                body["purpose"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.purpose", it) }
                body["created_at"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.response.file.created_at", it.toLong()) }
                body["expires_at"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.response.file.expires_at", it.toLong()) }
                body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.status", it) }
                body["bytes"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.response.file.size_bytes", it.toLong()) }
                body["status_details"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.status_details", it) }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
