/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the Anthropic Message Batches API.
 *
 * Detects the specific batch operation from the URL path and HTTP method, then sets the
 * `anthropic.api.type`, `gen_ai.operation.name`, and `gen_ai.request.batch.size` span attributes.
 *
 * ## Supported endpoints
 * - `POST /v1/messages/batches` → operation `create`
 * - `GET  /v1/messages/batches/{id}` → operation `retrieve`
 * - `POST /v1/messages/batches/{id}/cancel` → operation `cancel`
 *
 * See [Message Batches API](https://platform.claude.com/docs/en/api/creating-message-batches)
 */
internal class BatchesAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "batches")

        val operation = detectOperation(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, operation.operationName)

        // Extract batch size only for create requests (body contains "requests" array)
        if (operation == BatchOperation.CREATE) {
            request.body.asJson()?.jsonObject
                ?.get("requests")?.jsonArray?.size
                ?.let { span.setAttribute("gen_ai.request.batch.size", it.toLong()) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute("anthropic.api.type", "batches")

        val operation = detectOperation(response.url, response.requestMethod)
        span.setAttribute(GEN_AI_OPERATION_NAME, operation.operationName)

        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_RESPONSE_ID, it)
        }
        body["processing_status"]?.jsonPrimitive?.content?.let {
            span.setAttribute("anthropic.batch.processing_status", it)
        }

        body["request_counts"]?.jsonObject?.let { counts ->
            counts["processing"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("anthropic.batch.request_counts.processing", it.toLong())
            }
            counts["succeeded"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("anthropic.batch.request_counts.succeeded", it.toLong())
            }
            counts["errored"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("anthropic.batch.request_counts.errored", it.toLong())
            }
            counts["canceled"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("anthropic.batch.request_counts.canceled", it.toLong())
            }
            counts["expired"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("anthropic.batch.request_counts.expired", it.toLong())
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Batches API does not use server-sent events streaming
    }

    /**
     * Detects which batch operation is being performed based on the URL path and HTTP method.
     *
     * URL patterns:
     * - `POST /v1/messages/batches`            → [BatchOperation.CREATE]
     * - `GET  /v1/messages/batches/{id}`        → [BatchOperation.RETRIEVE]
     * - `POST /v1/messages/batches/{id}/cancel` → [BatchOperation.CANCEL]
     */
    private fun detectOperation(url: TracyHttpUrl, method: String): BatchOperation {
        val segments = url.pathSegments
        val batchesIndex = segments.indexOf("batches")

        if (batchesIndex == -1) {
            logger.warn { "Failed to detect batch operation: no 'batches' path segment in ${segments.joinToString("/")}" }
            return BatchOperation.CREATE
        }

        val hasBatchId = segments.size > (batchesIndex + 1) && segments[batchesIndex + 1].isNotBlank()
        val hasCancel = segments.lastOrNull() == "cancel"

        return when {
            method == "POST" && hasCancel -> BatchOperation.CANCEL
            method == "GET" && hasBatchId -> BatchOperation.RETRIEVE
            method == "POST" && !hasBatchId -> BatchOperation.CREATE
            else -> {
                logger.warn { "Failed to detect batch operation for: $method ${segments.joinToString("/")}" }
                BatchOperation.CREATE
            }
        }
    }

    private enum class BatchOperation(val operationName: String) {
        CREATE("create"),
        RETRIEVE("retrieve"),
        CANCEL("cancel"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
