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
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Batches API (create, retrieve, cancel, list).
 *
 * See [Batches API](https://platform.openai.com/docs/api-reference/batch)
 */
internal class BatchesOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operationName = resolveBatchesOperation(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
        span.setAttribute("openai.api.type", "batches")

        if (operationName == "batches.create") {
            val body = request.body.asJson()?.jsonObject ?: return
            body["input_file_id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.batch.input_file.id", it) }
            body["endpoint"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.batch.endpoint", it) }
            body["completion_window"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.batch.completion_window", it) }
            body["metadata"]?.jsonObject?.let { metadata ->
                span.setAttribute("tracy.request.metadata.keys", metadata.keys.toList().toString())
            }
            body["output_expires_after"]?.jsonObject?.let { expires ->
                expires["anchor"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.batch.output_expires_after.anchor", it) }
                expires["seconds"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.request.batch.output_expires_after.seconds", it.toLong()) }
            }
        } else if (operationName == "batches.list") {
            val params = request.url.parameters
            params.queryParameter("limit")?.toLongOrNull()?.let { span.setAttribute("tracy.request.limit", it) }
            params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val objectType = body["object"]?.jsonPrimitive?.contentOrNull

        when (objectType) {
            "batch" -> extractBatchAttributes(span, body)
            "list" -> {
                val data = body["data"]?.jsonArray
                span.setAttribute("tracy.response.list.count", (data?.size ?: 0).toLong())
                body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.has_more", it) }
                data?.firstOrNull()?.jsonObject?.let { extractBatchAttributes(span, it) }
            }
            else -> extractBatchAttributes(span, body)
        }
    }

    private fun extractBatchAttributes(span: Span, batch: JsonObject) {
        batch["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.batch.id", it) }
        batch["status"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.batch.status", it) }
        batch["created_at"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.batch.created_at", it) }
        batch["request_counts"]?.jsonObject?.let { counts ->
            counts["total"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.batch.request_counts.total", it.toLong()) }
            counts["completed"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.batch.request_counts.completed", it.toLong()) }
            counts["failed"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.batch.request_counts.failed", it.toLong()) }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private fun resolveBatchesOperation(url: TracyHttpUrl, method: String): String {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val batchesIndex = segments.indexOf("batches")
        val hasBatchId = batchesIndex >= 0 && segments.size > batchesIndex + 1
        val hasCancel = segments.contains("cancel")
        return when {
            method == "POST" && !hasBatchId -> "batches.create"
            method == "POST" && hasCancel -> "batches.cancel"
            method == "GET" && hasBatchId -> "batches.retrieve"
            else -> "batches.list"
        }
    }
}
