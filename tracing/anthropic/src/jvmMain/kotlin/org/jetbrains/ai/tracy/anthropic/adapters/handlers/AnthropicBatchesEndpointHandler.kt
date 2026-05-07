/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic Message Batches API.
 *
 * Detects the batch sub-route from the URL path and HTTP method, then sets the appropriate
 * span attributes for the operation.
 *
 * Supported sub-routes:
 * - `POST /messages/batches` → `batches.create`
 * - `GET /messages/batches/{id}` → `batches.retrieve`
 * - `POST /messages/batches/{id}/cancel` → `batches.cancel`
 *
 * ## Request Attributes
 * - `anthropic.api.type` = `"batches"`
 * - `gen_ai.operation.name` = `"batches.create"` / `"batches.retrieve"` / `"batches.cancel"`
 * - `gen_ai.request.batch.size` = number of items in the `requests` array (create only)
 *
 * ## Response Attributes
 * - `gen_ai.response.batch.id`
 * - `gen_ai.response.batch.processing_status`
 * - `gen_ai.response.batch.created_at`
 * - `gen_ai.response.batch.expires_at`
 * - `gen_ai.response.batch.request_counts.processing`
 * - `gen_ai.response.batch.request_counts.succeeded`
 * - `gen_ai.response.batch.request_counts.errored`
 * - `gen_ai.response.batch.request_counts.canceled`
 * - `gen_ai.response.batch.request_counts.expired`
 *
 * See: [Anthropic Message Batches API](https://docs.anthropic.com/en/api/creating-message-batches)
 */
internal class AnthropicBatchesEndpointHandler : EndpointApiHandler {

    private enum class Operation(val operationName: String) {
        CREATE("batches.create"),
        RETRIEVE("batches.retrieve"),
        CANCEL("batches.cancel"),
    }

    private fun detectOperation(url: TracyHttpUrl, method: String): Operation {
        val segments = url.pathSegments
        return when {
            // POST /messages/batches/{id}/cancel → last segment is "cancel"
            segments.lastOrNull() == "cancel" -> Operation.CANCEL
            // GET /messages/batches/{id} → GET method with an ID after "batches"
            method.uppercase() == "GET" -> Operation.RETRIEVE
            // POST /messages/batches → create (default for POST without cancel)
            else -> Operation.CREATE
        }
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operation = detectOperation(request.url, request.method)

        span.setAttribute("anthropic.api.type", "batches")
        span.setAttribute("gen_ai.operation.name", operation.operationName)

        if (operation == Operation.CREATE) {
            val body = request.body.asJson()?.jsonObject ?: return
            val requests = body["requests"]
            if (requests is JsonArray) {
                span.setAttribute("gen_ai.request.batch.size", requests.size.toLong())
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

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
}
