/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
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
 * Handler for OpenAI Batches API.
 *
 * The Batches API provides endpoints to create, retrieve, and cancel batches:
 * 1. `POST /v1/batches` - Create a batch
 * 2. `GET /v1/batches/{batch_id}` - Retrieve a batch
 * 3. `POST /v1/batches/{batch_id}/cancel` - Cancel a batch
 *
 * See [Batches API Reference](https://platform.openai.com/docs/api-reference/batch)
 */
internal class BatchesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operation = detectOperation(request.url, request.method)

        if (operation == BatchOperation.CREATE) {
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
            body["output_expires_after"]?.jsonObject?.let { expiresAfter ->
                expiresAfter["anchor"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.request.batch.output_expires_after.anchor", it)
                }
                expiresAfter["seconds"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.request.batch.output_expires_after.seconds", it)
                }
            }
            body["metadata"]?.jsonObject?.keys?.let { keys ->
                if (keys.isNotEmpty()) {
                    span.setAttribute("tracy.request.metadata.keys", keys.joinToString(","))
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val operation = detectOperation(response.url, response.requestMethod)

        // Override the operation name set by setCommonResponseAttributes
        span.setAttribute(GEN_AI_OPERATION_NAME, operation.operationName)

        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.batch.id", it)
        }
        body["status"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.batch.status", it)
        }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.batch.created_at", it)
        }
        body["request_counts"]?.jsonObject?.let { requestCounts ->
            requestCounts["total"]?.jsonPrimitive?.longOrNull?.let {
                span.setAttribute("tracy.batch.request_counts.total", it)
            }
            requestCounts["completed"]?.jsonPrimitive?.longOrNull?.let {
                span.setAttribute("tracy.batch.request_counts.completed", it)
            }
            requestCounts["failed"]?.jsonPrimitive?.longOrNull?.let {
                span.setAttribute("tracy.batch.request_counts.failed", it)
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Batches API does not use SSE streaming
    }

    /**
     * Detects which specific batch operation is being performed based on URL and HTTP method.
     *
     * URL patterns:
     * - `POST /v1/batches`                    → CREATE
     * - `GET /v1/batches/{batch_id}`           → RETRIEVE
     * - `POST /v1/batches/{batch_id}/cancel`   → CANCEL
     */
    private fun detectOperation(url: TracyHttpUrl, method: String): BatchOperation {
        val segments = url.pathSegments
        val batchesIndex = segments.indexOf("batches")

        if (batchesIndex == -1) {
            logger.warn { "Failed to detect batch operation. No 'batches' path segment: ${segments.joinToString("/")}" }
            return BatchOperation.CREATE
        }

        val hasBatchId = segments.size > (batchesIndex + 1) &&
                segments[batchesIndex + 1].isNotBlank()
        val hasCancel = segments.contains("cancel")

        return when {
            method == "POST" && !hasBatchId -> BatchOperation.CREATE
            method == "GET" && hasBatchId -> BatchOperation.RETRIEVE
            method == "POST" && hasBatchId && hasCancel -> BatchOperation.CANCEL
            else -> {
                logger.warn { "Unknown batch operation: $method ${segments.joinToString("/")}" }
                BatchOperation.CREATE
            }
        }
    }

    private enum class BatchOperation(val operationName: String) {
        CREATE("batches.create"),
        RETRIEVE("batches.retrieve"),
        CANCEL("batches.cancel"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
