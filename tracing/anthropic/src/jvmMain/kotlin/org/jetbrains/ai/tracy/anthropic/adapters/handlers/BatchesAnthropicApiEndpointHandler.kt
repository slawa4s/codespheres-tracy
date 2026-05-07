/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * - `POST /v1/messages/batches`               → `"create"`
 * - `GET  /v1/messages/batches`               → `"list"`
 * - `GET  /v1/messages/batches/{id}`          → `"retrieve"`
 * - `POST /v1/messages/batches/{id}/cancel`   → `"cancel"`
 * - `DELETE /v1/messages/batches/{id}`        → `"delete"`
 *
 * See: [Anthropic Message Batches API](https://platform.claude.com/docs/en/api/creating-message-batches)
 */
internal class BatchesAnthropicApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operation = detectOperation(request.url, request.method)
        span.setAttribute("gen_ai.operation.name", operation)

        if (operation == "create") {
            val body = request.body.asJson()?.jsonObject ?: return
            body["requests"]?.let { requests ->
                if (requests is JsonArray) {
                    span.setAttribute("gen_ai.batch.request_count", requests.size.toLong())
                    // Extract model from the first request's params for observability
                    requests.firstOrNull()?.jsonObject?.get("params")
                        ?.jsonObject?.get("model")?.jsonPrimitive?.content?.let {
                            span.setAttribute("gen_ai.request.model", it)
                        }
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // Single batch object response (create, retrieve, cancel, delete)
        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.id", it)
        }
        body["processing_status"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.batch.processing_status", it)
        }
        body["request_counts"]?.jsonObject?.let { counts ->
            counts["processing"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.batch.request_counts.processing", it.toLong())
            }
            counts["succeeded"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.batch.request_counts.succeeded", it.toLong())
            }
            counts["errored"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.batch.request_counts.errored", it.toLong())
            }
            counts["canceled"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.batch.request_counts.canceled", it.toLong())
            }
            counts["expired"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.batch.request_counts.expired", it.toLong())
            }
        }

        // List response
        body["data"]?.let { data ->
            if (data is JsonArray) {
                span.setAttribute("gen_ai.batch.list.count", data.size.toLong())
            }
        }
        body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("gen_ai.batch.list.has_more", it)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Batches API does not use server-sent events streaming" }
    }

    /**
     * Detects the batch API operation from the URL path and HTTP method.
     *
     * Segments after `batches` determine whether a batch ID is present:
     * - `POST`   + no ID   → `"create"`
     * - `GET`    + no ID   → `"list"`
     * - `GET`    + ID      → `"retrieve"`
     * - `POST`   + ID + `cancel` → `"cancel"`
     * - `DELETE` + ID      → `"delete"`
     */
    private fun detectOperation(url: TracyHttpUrl, method: String): String {
        val segments = url.pathSegments
        val batchesIndex = segments.indexOf("batches")
        if (batchesIndex == -1) {
            logger.warn { "No 'batches' segment in URL path: ${segments.joinToString("/")}" }
            return "create"
        }

        val afterBatches = segments.drop(batchesIndex + 1).filter { it.isNotBlank() }
        val hasBatchId = afterBatches.isNotEmpty()
        val hasCancel = afterBatches.contains("cancel")

        return when {
            method == "POST" && !hasBatchId -> "create"
            method == "GET" && !hasBatchId -> "list"
            method == "GET" && hasBatchId -> "retrieve"
            method == "POST" && hasBatchId && hasCancel -> "cancel"
            method == "DELETE" && hasBatchId -> "delete"
            else -> {
                logger.warn { "Unknown batch operation: $method /${segments.joinToString("/")}" }
                "create"
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
