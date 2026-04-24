/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Files API.
 *
 * Supports:
 * - `POST /files` — Upload a file (`files.create`)
 * - `DELETE /files/{file_id}` — Delete a file (`files.delete`)
 * - `GET /files` — List files (`files.list`)
 * - `GET /files/{file_id}` — Retrieve a file (`files.retrieve`)
 *
 * See [Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operationName = when (request.method) {
            "POST" -> "files.create"
            "DELETE" -> "files.delete"
            "GET" -> {
                val segments = request.url.pathSegments
                val filesIndex = segments.indexOf("files")
                if (filesIndex != -1 && segments.size > filesIndex + 1 && segments[filesIndex + 1].isNotBlank()) {
                    "files.retrieve"
                } else {
                    "files.list"
                }
            }
            else -> null
        }
        operationName?.let { span.setAttribute(GEN_AI_OPERATION_NAME, it) }

        val formData = request.body.asFormData() ?: return
        for (part in formData.parts) {
            when (part.name) {
                "purpose" -> {
                    val content = part.content.toString(part.contentType?.charset() ?: Charsets.UTF_8)
                    span.setAttribute("gen_ai.request.file.purpose", content)
                }
                "file" -> {
                    span.setAttribute("gen_ai.request.file.size_bytes", part.content.size.toLong())
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("gen_ai.response.list.count", data.size.toLong())
        } else {
            body["id"]?.let { span.setAttribute("gen_ai.response.file.id", it.jsonPrimitive.content) }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Files API does not use SSE streaming
    }
}
