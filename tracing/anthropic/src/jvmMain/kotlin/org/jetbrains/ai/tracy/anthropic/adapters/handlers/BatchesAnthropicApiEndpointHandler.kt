/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic Message Batches API.
 *
 * Maps HTTP method + URL path to `gen_ai.operation.name`:
 * - `POST /v1/messages/batches`               → `"batches.create"`
 * - `GET  /v1/messages/batches`               → `"batches.list"`
 * - `GET  /v1/messages/batches/{id}`          → `"batches.retrieve"`
 * - `POST /v1/messages/batches/{id}/cancel`   → `"batches.cancel"`
 * - `DELETE /v1/messages/batches/{id}`        → `"batches.delete"`
 *
 * Maps batch response fields to `gen_ai.response.batch.*` OTel attributes.
 *
 * See: [Message Batches API](https://docs.anthropic.com/en/api/creating-message-batches)
 */
internal class BatchesAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "batches")

        val operation = detectOperation(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        if (operation == "batches.create") {
            val body = request.body.asJson()?.jsonObject
            body?.get("requests")?.let { requests ->
                if (requests is JsonArray) {
                    span.setAttribute("gen_ai.request.batch.size", requests.size.toLong())
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        span.setAttribute(GEN_AI_OUTPUT_TYPE, "message_batch")

        // id → gen_ai.response.id + gen_ai.response.batch.id
        body["id"]?.jsonPrimitive?.content?.let { id ->
            span.setAttribute(GEN_AI_RESPONSE_ID, id)
            span.setAttribute("gen_ai.response.batch.id", id)
        }

        // processing_status → gen_ai.response.batch.processing_status
        body["processing_status"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.batch.processing_status", it)
        }

        // created_at → gen_ai.response.batch.created_at (ISO-8601 string)
        body["created_at"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.batch.created_at", it)
        }

        // expires_at → gen_ai.response.batch.expires_at (ISO-8601 string)
        body["expires_at"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.batch.expires_at", it)
        }

        // request_counts.{processing,succeeded,errored,canceled,expired}
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

    override fun handleStreaming(span: Span, events: String) {
        // Batches API does not use SSE streaming
    }

    /**
     * Detects which batch operation is being called based on the URL path and HTTP method.
     *
     * URL patterns:
     * - `POST   .../batches`              → `"batches.create"`
     * - `GET    .../batches`              → `"batches.list"`
     * - `GET    .../batches/{id}`         → `"batches.retrieve"`
     * - `POST   .../batches/{id}/cancel`  → `"batches.cancel"`
     * - `DELETE .../batches/{id}`         → `"batches.delete"`
     */
    internal fun detectOperation(url: TracyHttpUrl, method: String): String {
        val segments = url.pathSegments
        val batchesIndex = segments.indexOf("batches")
        if (batchesIndex == -1) {
            logger.warn { "No 'batches' segment in URL path: ${segments.joinToString("/")}" }
            return "batches.create"
        }

        val afterBatches = segments.drop(batchesIndex + 1).filter { it.isNotBlank() }
        val hasBatchId = afterBatches.isNotEmpty() && afterBatches.first() != "cancel"
        val hasCancel = afterBatches.contains("cancel")

        return when {
            method == "POST" && !hasBatchId && !hasCancel -> "batches.create"
            method == "GET" && !hasBatchId -> "batches.list"
            method == "GET" && hasBatchId -> "batches.retrieve"
            method == "POST" && hasBatchId && hasCancel -> "batches.cancel"
            method == "DELETE" && hasBatchId -> "batches.delete"
            else -> {
                logger.warn { "Unknown batch operation: $method /${segments.joinToString("/")}" }
                "batches.create"
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
