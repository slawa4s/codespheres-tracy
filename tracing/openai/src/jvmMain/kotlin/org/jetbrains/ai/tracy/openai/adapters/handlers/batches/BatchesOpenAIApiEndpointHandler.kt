/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
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
 * Handler for OpenAI Batches API.
 *
 * The Batches API provides endpoints to create and manage asynchronous batch jobs:
 * 1. `POST /batches` - Create a batch job
 * 2. `GET /batches/{batch_id}` - Retrieve a batch job's status and metadata
 * 3. `POST /batches/{batch_id}/cancel` - Cancel an in-progress batch job
 * 4. `GET /batches` - List all batch jobs
 *
 * This handler detects the specific route from the URL and HTTP method, stores the
 * resolved operation name in a [ThreadLocal] during the request phase, and re-applies
 * it in the response phase to override what [OpenAIApiUtils.setCommonResponseAttributes]
 * sets from the response `object` field (e.g. `"batch"`, `"list"`).
 *
 * See [Batch API Reference](https://platform.openai.com/docs/api-reference/batch)
 */
internal class BatchesOpenAIApiEndpointHandler : EndpointApiHandler {

    /**
     * Stores the operation name detected during request handling so it can be
     * re-applied in [handleResponseAttributes] after [setCommonResponseAttributes]
     * overwrites [GEN_AI_OPERATION_NAME] with the raw JSON `object` field.
     */
    private val operationNameThreadLocal = ThreadLocal<String>()

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "batches")

        val route = detectRoute(request.url, request.method)
        operationNameThreadLocal.set(route.operationName)

        val segments = request.url.pathSegments
        val batchesIndex = segments.indexOf("batches")

        // Extract batch_id from URL path for non-CREATE and non-LIST routes
        if (route != BatchRoute.CREATE && route != BatchRoute.LIST &&
            batchesIndex != -1 && segments.size > batchesIndex + 1
        ) {
            val batchId = segments[batchesIndex + 1]
            if (batchId.isNotBlank()) {
                span.setAttribute("tracy.batch.id", batchId)
            }
        }

        when (route) {
            BatchRoute.CREATE -> {
                // POST /batches sends JSON body: { input_file_id, endpoint, completion_window }
                val body = request.body.asJson()?.jsonObject ?: return
                body["input_file_id"]?.let {
                    span.setAttribute("tracy.request.input_file_id", it.jsonPrimitive.content)
                }
                body["endpoint"]?.let {
                    span.setAttribute("tracy.request.endpoint", it.jsonPrimitive.content)
                }
                body["completion_window"]?.let {
                    span.setAttribute("tracy.request.completion_window", it.jsonPrimitive.content)
                }
            }

            BatchRoute.LIST -> {
                // GET /batches accepts pagination query parameters
                val params = request.url.parameters
                params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
                params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
            }

            else -> {
                // RETRIEVE, CANCEL: batch_id is already extracted from URL above
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // Re-apply the correct operation name, overriding what setCommonResponseAttributes
        // set from the JSON `object` field (e.g. "batch", "list").
        span.setAttribute(GEN_AI_OPERATION_NAME, operationNameThreadLocal.get() ?: BatchRoute.CREATE.operationName)
        operationNameThreadLocal.remove()

        val body = response.body.asJson()?.jsonObject ?: return
        val route = detectRoute(response.url, response.requestMethod)

        when (route) {
            BatchRoute.LIST -> {
                // Response: { object: "list", data: [...], first_id, last_id, has_more }
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("tracy.batch.count", data.size.toLong())
                }
            }

            else -> {
                // BatchRoute.CREATE, BatchRoute.RETRIEVE, BatchRoute.CANCEL
                // Response: Batch { id, object, endpoint, errors, input_file_id,
                //   completion_window, status, created_at, request_counts, ... }
                body["id"]?.let {
                    val id = it.jsonPrimitive.content
                    span.setAttribute("tracy.batch.id", id)
                    span.setAttribute(GEN_AI_RESPONSE_ID, id)
                }
                body["status"]?.let { span.setAttribute("tracy.batch.status", it.jsonPrimitive.content) }
                body["input_file_id"]?.let {
                    span.setAttribute("tracy.batch.input_file_id", it.jsonPrimitive.content)
                }
                body["endpoint"]?.let { span.setAttribute("tracy.batch.endpoint", it.jsonPrimitive.content) }
                body["completion_window"]?.let {
                    span.setAttribute("tracy.batch.completion_window", it.jsonPrimitive.content)
                }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.batch.created_at", it)
                }
                // Request counts: { total, completed, failed }
                body["request_counts"]?.jsonObject?.let { counts ->
                    counts["total"]?.jsonPrimitive?.longOrNull?.let {
                        span.setAttribute("tracy.batch.request_counts.total", it)
                    }
                    counts["completed"]?.jsonPrimitive?.longOrNull?.let {
                        span.setAttribute("tracy.batch.request_counts.completed", it)
                    }
                    counts["failed"]?.jsonPrimitive?.longOrNull?.let {
                        span.setAttribute("tracy.batch.request_counts.failed", it)
                    }
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Batches API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific Batches API endpoint is being called based on the URL path and HTTP method.
     *
     * URL patterns:
     * - `POST /batches`                     → CREATE
     * - `GET  /batches/{batch_id}`          → RETRIEVE
     * - `POST /batches/{batch_id}/cancel`   → CANCEL
     * - `GET  /batches`                     → LIST
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): BatchRoute {
        val segments = url.pathSegments
        val batchesIndex = segments.indexOf("batches")

        if (batchesIndex == -1) {
            logger.warn { "Failed to detect batches route. Endpoint has no `batches` path segment: ${url.pathSegments.joinToString(separator = "/")}" }
            return BatchRoute.CREATE
        }

        val hasBatchId = segments.size > (batchesIndex + 1) &&
                segments[batchesIndex + 1].isNotBlank()
        val hasCancel = segments.contains("cancel")

        return when {
            method == "POST" && !hasBatchId -> BatchRoute.CREATE
            method == "GET" && !hasBatchId -> BatchRoute.LIST
            method == "GET" && hasBatchId -> BatchRoute.RETRIEVE
            method == "POST" && hasBatchId && hasCancel -> BatchRoute.CANCEL
            else -> {
                logger.warn { "Failed to detect batches route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                BatchRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different Batches API routes.
     */
    private enum class BatchRoute(val operationName: String) {
        CREATE("batches.create"),
        LIST("batches.list"),
        RETRIEVE("batches.retrieve"),
        CANCEL("batches.cancel"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
