/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the Anthropic Message Batches API (`/v1/messages/batches`).
 *
 * Detects all four batch sub-routes and sets the correct span attributes for each:
 * 1. `POST /messages/batches`              → BATCH_CREATE
 * 2. `GET  /messages/batches/{id}`         → BATCH_RETRIEVE
 * 3. `POST /messages/batches/{id}/cancel`  → BATCH_CANCEL
 * 4. `GET  /messages/batches`              → BATCH_LIST
 *
 * Always sets `anthropic.api.type = "batches"` and `gen_ai.operation.name` to the
 * route-derived value (e.g. "batches.create").
 *
 * See: [Anthropic Message Batches API](https://platform.claude.com/docs/en/api/message-batches)
 */
internal class BatchesAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        span.setAttribute("anthropic.api.type", "batches")
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        if (route == BatchRoute.CREATE) {
            val body = request.body.asJson()?.jsonObject ?: return
            val requests = body["requests"]
            if (requests is JsonArray) {
                span.setAttribute("gen_ai.request.batch.size", requests.size.toLong())
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        span.setAttribute("anthropic.api.type", "batches")
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

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

    override fun handleStreaming(span: Span, events: String) {
        // Message Batches API does not use server-sent events streaming
        logger.warn { "Message Batches API does not use server-sent events streaming" }
    }

    /**
     * Internal enum distinguishing all four batch API routes.
     */
    internal enum class BatchRoute(val operationName: String) {
        CREATE("batches.create"),
        RETRIEVE("batches.retrieve"),
        CANCEL("batches.cancel"),
        LIST("batches.list")
    }

    /**
     * Detects the batch route from the URL path segments and HTTP method.
     *
     * Route detection logic:
     * - `POST /messages/batches`              → CREATE
     * - `GET  /messages/batches/{id}`         → RETRIEVE
     * - `POST /messages/batches/{id}/cancel`  → CANCEL
     * - `GET  /messages/batches`              → LIST
     */
    internal fun detectRoute(url: TracyHttpUrl, method: String): BatchRoute {
        val segments = url.pathSegments
        val batchesIndex = segments.indexOf("batches")

        if (batchesIndex == -1) {
            logger.warn {
                "Failed to detect batch route: no 'batches' segment in ${segments.joinToString("/")}"
            }
            return BatchRoute.LIST
        }

        val hasBatchId = segments.size > batchesIndex + 1 && segments[batchesIndex + 1].isNotBlank()
        val hasCancel = segments.contains("cancel")

        return when {
            method == "POST" && !hasBatchId && !hasCancel -> BatchRoute.CREATE
            method == "GET" && hasBatchId && !hasCancel -> BatchRoute.RETRIEVE
            method == "POST" && hasBatchId && hasCancel -> BatchRoute.CANCEL
            method == "GET" && !hasBatchId -> BatchRoute.LIST
            else -> {
                logger.warn { "Failed to detect batch route: $method ${segments.joinToString("/")}" }
                BatchRoute.LIST
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
