/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for Anthropic Message Batches API.
 *
 * Maps batch lifecycle and list operations to OpenTelemetry span attributes following
 * the `gen_ai.operation.name` / `gen_ai.request.batch.*` / `gen_ai.response.batch.*` /
 * `gen_ai.response.list.*` naming convention.
 *
 * Supported URL patterns and the operation names they produce:
 * | Method | Path                                    | Operation  |
 * |--------|-----------------------------------------|------------|
 * | POST   | /v1/messages/batches                    | create     |
 * | GET    | /v1/messages/batches                    | list       |
 * | GET    | /v1/messages/batches/{id}               | retrieve   |
 * | POST   | /v1/messages/batches/{id}/cancel        | cancel     |
 *
 * See: [Anthropic Message Batches API](https://docs.anthropic.com/en/api/creating-message-batches)
 */
internal class AnthropicBatchApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operationName = detectOperation(request.method, request.url.pathSegments)
        span.setAttribute("gen_ai.operation.name", operationName)

        if (operationName == "create") {
            val body = request.body.asJson()?.jsonObject ?: return
            body["requests"]?.jsonArray?.size?.let { size ->
                span.setAttribute("gen_ai.request.batch.size", size.toLong())
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val operationName = detectOperation(response.requestMethod, response.url.pathSegments)

        if (operationName == "list") {
            body["data"]?.jsonArray?.size?.let { count ->
                span.setAttribute("gen_ai.response.list.count", count.toLong())
            }
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { hasMore ->
                span.setAttribute("gen_ai.response.list.has_more", hasMore)
            }
            body["first_id"]?.jsonPrimitive?.contentOrNull?.let { firstId ->
                span.setAttribute("gen_ai.response.list.first_id", firstId)
            }
            body["last_id"]?.jsonPrimitive?.contentOrNull?.let { lastId ->
                span.setAttribute("gen_ai.response.list.last_id", lastId)
            }
        } else {
            body["id"]?.jsonPrimitive?.contentOrNull?.let { id ->
                span.setAttribute("gen_ai.response.batch.id", id)
            }
            body["processing_status"]?.jsonPrimitive?.contentOrNull?.let { status ->
                span.setAttribute("gen_ai.response.batch.processing_status", status)
            }
            body["created_at"]?.jsonPrimitive?.contentOrNull?.let { createdAt ->
                span.setAttribute("gen_ai.response.batch.created_at", createdAt)
            }
            body["expires_at"]?.jsonPrimitive?.contentOrNull?.let { expiresAt ->
                span.setAttribute("gen_ai.response.batch.expires_at", expiresAt)
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
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    companion object {
        /**
         * Infers the batch operation name from the HTTP method and URL path segments.
         *
         * Returns one of: `create`, `list`, `retrieve`, `cancel`, or `unknown`.
         */
        internal fun detectOperation(method: String, pathSegments: List<String>): String {
            val batchesIdx = pathSegments.indexOf("batches")
            if (batchesIdx < 0) return "unknown"
            val afterBatches = pathSegments.drop(batchesIdx + 1).filter { it.isNotEmpty() }

            return when {
                method.equals("POST", ignoreCase = true) && afterBatches.isEmpty() -> "create"
                method.equals("GET", ignoreCase = true) && afterBatches.isEmpty() -> "list"
                method.equals("GET", ignoreCase = true) && afterBatches.size == 1 -> "retrieve"
                method.equals("POST", ignoreCase = true) && afterBatches.lastOrNull() == "cancel" -> "cancel"
                else -> "unknown"
            }
        }
    }
}
