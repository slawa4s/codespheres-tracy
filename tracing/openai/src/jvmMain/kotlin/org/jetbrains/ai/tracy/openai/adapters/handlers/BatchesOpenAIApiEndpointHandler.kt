/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Batches API.
 *
 * Handles create, retrieve, list, and cancel operations on `/v1/batches`.
 *
 * See: [Batches API](https://platform.openai.com/docs/api-reference/batch)
 */
internal class BatchesOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "batches")
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        val batchIdx = segments.indexOf("batches")
        val hasBatchId = batchIdx >= 0 && segments.size > batchIdx + 1

        val operation = when {
            hasBatchId && request.method == "POST" -> "batches.cancel"
            hasBatchId -> "batches.retrieve"
            request.method == "GET" -> "batches.list"
            else -> "batches.create"
        }
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        if (operation == "batches.create") {
            val body = request.body.asJson()?.jsonObject ?: return
            body["endpoint"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.batch.endpoint", it)
            }
            body["completion_window"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.batch.completion_window", it)
            }
            body["input_file_id"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.batch.input_file.id", it)
            }
            body["metadata"]?.jsonObject?.let { metadata ->
                span.setAttribute("tracy.request.metadata.keys", metadata.keys.sorted().joinToString(","))
            }
            body["output_expires_after"]?.jsonObject?.let { expiresAfter ->
                expiresAfter["anchor"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.request.batch.output_expires_after.anchor", it)
                }
                expiresAfter["seconds"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("tracy.request.batch.output_expires_after.seconds", it.toLong())
                }
            }
        } else if (operation == "batches.list") {
            request.url.parameters.queryParameter("limit")?.toLongOrNull()?.let {
                span.setAttribute("tracy.request.limit", it)
            }
            request.url.parameters.queryParameter("after")?.let {
                span.setAttribute("tracy.request.after", it)
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonResponseAttributes(span, response)
        span.setAttribute("openai.api.type", "batches")

        val segments = response.url.pathSegments.filter { it.isNotEmpty() }
        val batchIdx = segments.indexOf("batches")
        val hasBatchId = batchIdx >= 0 && segments.size > batchIdx + 1

        val operation = when {
            hasBatchId && response.requestMethod == "POST" -> "batches.cancel"
            hasBatchId -> "batches.retrieve"
            response.requestMethod == "GET" -> "batches.list"
            else -> "batches.create"
        }
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        if (operation == "batches.list") {
            body["data"]?.let { data ->
                if (data is JsonArray) span.setAttribute("tracy.response.list.count", data.size.toLong())
            }
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("tracy.response.has_more", it)
            }
            return
        }

        // Single batch object (create, retrieve, cancel)
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.batch.id", it) }
        body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.batch.status", it) }
        body["created_at"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.batch.created_at", it.toLong()) }
        body["request_counts"]?.jsonObject?.let { counts ->
            counts["total"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.batch.request_counts.total", it.toLong()) }
            counts["completed"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.batch.request_counts.completed", it.toLong()) }
            counts["failed"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.batch.request_counts.failed", it.toLong()) }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
