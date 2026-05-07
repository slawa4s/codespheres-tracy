/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic list endpoints (batches, files, models).
 *
 * Sets stable semconv network attributes on requests and parses the standard Anthropic page
 * envelope on responses (data count, has_more, first_id, last_id). Sets `gen_ai.operation.name`
 * and `anthropic.api.type` for all recognised batch lifecycle operations:
 * - GET    `…/{type}`       → `{type}.list`
 * - GET    `…/{type}/{id}`  → `{type}.retrieve`
 * - POST   `…/{type}`       → `{type}.create`
 * - POST   `…/cancel`       → `batches.cancel`
 * - DELETE `…/{type}/{id}`  → `{type}.delete`
 *
 * For POST requests to the files endpoint with multipart/form-data body, extracts the uploaded
 * file's `gen_ai.request.file.filename`, `gen_ai.request.file.mime_type`, and
 * `gen_ai.request.file.size_bytes` from the `file` form part.
 *
 * See:
 * - [Messages Batches API](https://docs.anthropic.com/en/api/messages-batches)
 * - [Files API](https://docs.anthropic.com/en/api/files)
 * - [Models API](https://docs.anthropic.com/en/api/models)
 */
internal class AnthropicListEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gen_ai.provider.name", "anthropic")
        span.setAttribute("server.address", request.url.host)
        span.setAttribute("server.port", if (request.url.scheme == "https") 443L else 80L)

        val detectedType = detectApiType(request.url.pathSegments)
        span.setAttribute("anthropic.api.type", detectedType)

        val lastSegment = request.url.pathSegments.lastOrNull()
        val operationName = when (request.method) {
            "POST" -> when {
                lastSegment == "cancel" -> "batches.cancel"
                lastSegment == detectedType -> "$detectedType.create"
                else -> null
            }
            "GET" -> when {
                lastSegment == detectedType -> "$detectedType.list"
                else -> "$detectedType.retrieve"
            }
            "DELETE" -> "$detectedType.delete"
            else -> null
        }
        if (operationName != null) {
            span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
        }

        if (request.method == "POST" && detectedType == "batches" && lastSegment == "batches") {
            val requestsArray = request.body.asJson()?.jsonObject?.get("requests") as? JsonArray
            if (requestsArray != null) {
                span.setAttribute("gen_ai.request.batch.size", requestsArray.size.toLong())
            }
        }

        if (request.method == "POST" && detectedType == "files" && lastSegment == "files") {
            val formData = request.body.asFormData()
            if (formData != null) {
                for (part in formData.parts) {
                    if (part.name == "file") {
                        part.filename?.let { span.setAttribute("gen_ai.request.file.filename", it) }
                        part.contentType?.let { span.setAttribute("gen_ai.request.file.mime_type", it.asString()) }
                        span.setAttribute("gen_ai.request.file.size_bytes", part.content.size.toLong())
                    }
                }
            }
        }

        if (detectedType == "models" && lastSegment != null && lastSegment != "models") {
            span.setAttribute(GEN_AI_REQUEST_MODEL, lastSegment)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute("http.response.status_code", response.code.toLong())

        if (response.code >= 400) {
            val errorType = try {
                val body = response.body.asJson()?.jsonObject
                body?.get("error")?.jsonObject?.get("type")?.jsonPrimitive?.content
                    ?: body?.get("type")?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
            errorType?.let { span.setAttribute("error.type", it) }
            return
        }

        val body = response.body.asJson()?.jsonObject ?: return

        body["data"]?.jsonArray?.size?.toLong()?.let {
            span.setAttribute("gen_ai.response.list.count", it)
        }
        body["has_more"]?.jsonPrimitive?.boolean?.let {
            span.setAttribute("gen_ai.response.list.has_more", it)
        }
        body["first_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.list.first_id", it)
        }
        body["last_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.list.last_id", it)
        }

        if (body["type"]?.jsonPrimitive?.content == "message_batch") {
            span.setAttribute(GEN_AI_OUTPUT_TYPE, "message_batch")
            body["id"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.batch.id", it)
            }
            body["processing_status"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.batch.processing_status", it)
            }
            body["created_at"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.batch.created_at", it)
            }
            body["expires_at"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.batch.expires_at", it)
            }
            body["request_counts"]?.jsonObject?.let { counts ->
                counts["processing"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("gen_ai.response.batch.request_counts.processing", it)
                }
                counts["succeeded"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("gen_ai.response.batch.request_counts.succeeded", it)
                }
                counts["errored"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("gen_ai.response.batch.request_counts.errored", it)
                }
                counts["canceled"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("gen_ai.response.batch.request_counts.canceled", it)
                }
                counts["expired"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("gen_ai.response.batch.request_counts.expired", it)
                }
            }
        }

        if (body["type"]?.jsonPrimitive?.content == "model") {
            body["id"]?.jsonPrimitive?.content?.let { id ->
                span.setAttribute(GEN_AI_RESPONSE_MODEL, id)
                span.setAttribute("gen_ai.response.model.id", id)
            }
            span.setAttribute(GEN_AI_OUTPUT_TYPE, "model")
            body["display_name"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.model.display_name", it)
            }
            body["created_at"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.model.created_at", it)
            }
            body["max_input_tokens"]?.jsonPrimitive?.longOrNull?.let {
                span.setAttribute("gen_ai.response.model.max_input_tokens", it)
            }
            body["max_output_tokens"]?.jsonPrimitive?.longOrNull?.let {
                span.setAttribute("gen_ai.response.model.max_output_tokens", it)
            }
            body["capabilities"]?.jsonObject?.let { caps ->
                caps["batch"]?.jsonPrimitive?.booleanOrNull?.let {
                    span.setAttribute("gen_ai.response.model.capabilities.batch", it)
                }
                caps["citations"]?.jsonPrimitive?.booleanOrNull?.let {
                    span.setAttribute("gen_ai.response.model.capabilities.citations", it)
                }
                caps["vision"]?.jsonPrimitive?.booleanOrNull?.let {
                    span.setAttribute("gen_ai.response.model.capabilities.vision", it)
                }
            }
        }

        if (body["type"]?.jsonPrimitive?.content == "file") {
            span.setAttribute(GEN_AI_OUTPUT_TYPE, "file")
            val fileId = body["file_id"]?.jsonPrimitive?.content
                ?: body["id"]?.jsonPrimitive?.content
            fileId?.let { span.setAttribute("gen_ai.response.file.id", it) }
            body["filename"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.file.filename", it)
            }
            (body["mime_type"] ?: body["media_type"])?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.file.mime_type", it)
            }
            body["size"]?.jsonPrimitive?.longOrNull?.let {
                span.setAttribute("gen_ai.response.file.size_bytes", it)
            }
            body["downloadable"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.file.downloadable", it)
            }
            body["created_at"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.file.created_at", it)
            }
        }

        if (body["deleted"]?.jsonPrimitive?.booleanOrNull == true) {
            span.setAttribute(GEN_AI_OUTPUT_TYPE, "file_deleted")
            body["id"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.file.id", it)
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private fun detectApiType(pathSegments: List<String>): String = when {
        "batches" in pathSegments -> "batches"
        "files" in pathSegments -> "files"
        "models" in pathSegments -> "models"
        else -> "messages"
    }
}
