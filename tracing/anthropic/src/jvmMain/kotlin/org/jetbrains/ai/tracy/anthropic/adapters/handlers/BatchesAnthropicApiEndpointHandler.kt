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
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic Message Batches API.
 *
 * Extracts OTel-standard batch attributes from requests and responses for the following endpoints:
 * - POST `/v1/messages/batches` → operation `batch.create`
 * - GET `/v1/messages/batches` → operation `batch.list`
 * - GET `/v1/messages/batches/{id}` → operation `batch.retrieve`
 * - POST `/v1/messages/batches/{id}/cancel` → operation `batch.cancel`
 *
 * See: [Anthropic Message Batches API](https://docs.anthropic.com/en/api/creating-message-batches)
 */
internal class BatchesAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operation = detectOperation(request)
        span.setAttribute("gen_ai.operation.name", operation)

        if (operation == BATCH_CREATE) {
            val body = request.body.asJson()?.jsonObject ?: return
            body["requests"]?.jsonArray?.size?.let { size ->
                span.setAttribute("gen_ai.request.batch.size", size.toLong())
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
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.batch.created_at", it)
        }
        body["expires_at"]?.jsonPrimitive?.longOrNull?.let {
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

    override fun handleStreaming(span: Span, events: String): Unit = Unit

    /**
     * Detects the batch operation type from the request URL and HTTP method.
     *
     * Detection rules based on the last non-empty path segment and HTTP method:
     * - POST ending in `batches` → [BATCH_CREATE]
     * - GET ending in `batches` → [BATCH_LIST]
     * - POST ending in `cancel` → [BATCH_CANCEL]
     * - GET with a batch-id segment → [BATCH_RETRIEVE]
     */
    internal fun detectOperation(request: TracyHttpRequest): String {
        val pathSegments = request.url.pathSegments.filter { it.isNotEmpty() }
        val method = request.method.uppercase()
        val lastSegment = pathSegments.lastOrNull() ?: ""

        return when {
            method == "POST" && lastSegment == "batches" -> BATCH_CREATE
            method == "GET" && lastSegment == "batches" -> BATCH_LIST
            method == "POST" && lastSegment == "cancel" -> BATCH_CANCEL
            method == "GET" -> BATCH_RETRIEVE
            else -> "batch.unknown"
        }
    }

    companion object {
        const val BATCH_CREATE = "batch.create"
        const val BATCH_LIST = "batch.list"
        const val BATCH_RETRIEVE = "batch.retrieve"
        const val BATCH_CANCEL = "batch.cancel"
    }
}
