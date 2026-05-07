/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
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
 * Traces lifecycle operations on message batch resources:
 * - `POST /v1/messages/batches`              → [BatchRoute.CREATE]
 * - `GET /v1/messages/batches/{id}`          → [BatchRoute.RETRIEVE]
 * - `POST /v1/messages/batches/{id}/cancel`  → [BatchRoute.CANCEL]
 *
 * Maps batch response fields to `gen_ai.response.batch.*` OTel attributes so that
 * evaluators can assert on standard attribute names rather than unmapped `tracy.response.*` keys.
 *
 * See: [Message Batches API](https://docs.anthropic.com/en/api/creating-message-batches)
 */
internal class BatchesAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        // For retrieve/cancel, expose the batch ID from the URL path for correlation
        extractBatchId(request.url)?.let {
            span.setAttribute("gen_ai.request.batch.id", it)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        val route = detectRoute(response.url, response.requestMethod)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
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
        logger.warn { "Batches API does not use server-sent events streaming" }
    }

    /**
     * Detects which batch operation is being called based on URL path segments and HTTP method.
     *
     * URL patterns (relative to base):
     * - `POST .../batches`           → [BatchRoute.CREATE]
     * - `GET  .../batches/{id}`      → [BatchRoute.RETRIEVE]
     * - `POST .../batches/{id}/cancel` → [BatchRoute.CANCEL]
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): BatchRoute {
        val segments = url.pathSegments
        val batchesIndex = segments.indexOf("batches")

        if (batchesIndex == -1) {
            logger.warn {
                "Failed to detect batch route – no `batches` segment in: ${
                    segments.joinToString("/")
                }"
            }
            return BatchRoute.CREATE
        }

        val hasBatchId = segments.size > batchesIndex + 1 &&
                segments[batchesIndex + 1].isNotBlank()
        val hasCancel = segments.contains("cancel")

        return when {
            method == "POST" && !hasBatchId -> BatchRoute.CREATE
            method == "GET" && hasBatchId -> BatchRoute.RETRIEVE
            method == "POST" && hasBatchId && hasCancel -> BatchRoute.CANCEL
            else -> {
                logger.warn { "Unknown batch route: $method ${segments.joinToString("/")}" }
                BatchRoute.CREATE
            }
        }
    }

    /**
     * Extracts the batch ID from URL path segments, e.g.
     * `.../batches/msgbatch_01234` → `"msgbatch_01234"`.
     * Returns `null` for create requests (no ID in path).
     */
    private fun extractBatchId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val batchesIndex = segments.indexOf("batches")
        if (batchesIndex == -1 || segments.size <= batchesIndex + 1) return null
        val candidate = segments[batchesIndex + 1]
        return if (candidate.isNotBlank() && candidate != "cancel") candidate else null
    }

    private enum class BatchRoute(val operationName: String) {
        CREATE("batches.create"),
        RETRIEVE("batches.retrieve"),
        CANCEL("batches.cancel"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
