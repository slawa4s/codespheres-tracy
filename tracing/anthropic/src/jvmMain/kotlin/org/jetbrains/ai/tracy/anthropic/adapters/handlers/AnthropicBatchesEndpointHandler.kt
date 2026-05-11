/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Endpoint handler for the Anthropic Message Batches API.
 *
 * Covers five operations detected from the HTTP method and URL path:
 * - `batches.create`   — POST   /v1/messages/batches
 * - `batches.retrieve` — GET    /v1/messages/batches/{id}
 * - `batches.delete`   — DELETE /v1/messages/batches/{id}
 * - `batches.cancel`   — POST   /v1/messages/batches/{id}/cancel
 * - `batches.results`  — GET    /v1/messages/batches/{id}/results
 *
 * See: [Anthropic Message Batches API](https://docs.anthropic.com/en/api/creating-message-batches)
 */
internal class AnthropicBatchesEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operation = detectOperation(request.url.pathSegments, request.method)
        span.setAttribute("anthropic.api.type", "batches")
        span.setAttribute("gen_ai.operation.name", operation)

        if (operation == "batches.create") {
            runCatching {
                val body = request.body.asJson()?.jsonObject ?: return@runCatching
                body["requests"]?.jsonArray?.size?.let { count ->
                    span.setAttribute("anthropic.request.batch.size", count.toLong())
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        if (response.isError()) return
        val body = response.body.asJson()?.jsonObject ?: return
        span.setAttribute("gen_ai.output.type", body["type"]?.jsonPrimitive?.content ?: "message_batch")

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
            counts["processing"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.response.batch.request_counts.processing", it.toLong())
            }
            counts["succeeded"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.response.batch.request_counts.succeeded", it.toLong())
            }
            counts["errored"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.response.batch.request_counts.errored", it.toLong())
            }
            counts["canceled"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.response.batch.request_counts.canceled", it.toLong())
            }
            counts["expired"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.response.batch.request_counts.expired", it.toLong())
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private fun detectOperation(pathSegments: List<String>, method: String): String {
        val batchesIdx = pathSegments.indexOf("batches")
        val afterBatches = if (batchesIdx >= 0) pathSegments.drop(batchesIdx + 1) else emptyList()
        return when {
            afterBatches.lastOrNull() == "cancel" -> "batches.cancel"
            afterBatches.lastOrNull() == "results" -> "batches.results"
            method.uppercase() == "POST" && afterBatches.isEmpty() -> "batches.create"
            method.uppercase() == "DELETE" && afterBatches.isNotEmpty() -> "batches.delete"
            else -> "batches.retrieve"
        }
    }
}
