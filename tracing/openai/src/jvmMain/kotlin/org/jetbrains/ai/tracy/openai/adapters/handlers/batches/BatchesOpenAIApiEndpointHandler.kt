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
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils

/**
 * Handler for the OpenAI Batches API.
 *
 * The Batches API provides endpoints for managing batch inference jobs:
 * 1. `POST /v1/batches` - Create a batch (`batches.create`)
 * 2. `GET /v1/batches/{batch_id}` - Retrieve a batch (`batches.retrieve`)
 * 3. `POST /v1/batches/{batch_id}/cancel` - Cancel a batch (`batches.cancel`)
 * 4. `GET /v1/batches` - List batches (`batches.list`)
 *
 * See [Batches API Reference](https://platform.openai.com/docs/api-reference/batch)
 */
internal class BatchesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setNetworkRequestAttributes(span, request)
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        span.setAttribute("openai.api.type", "batches")

        if (route == BatchRoute.CREATE) {
            val body = request.body.asJson()?.jsonObject ?: return
            body["input_file_id"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.batch.input_file.id", it)
            }
            body["endpoint"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.batch.endpoint", it)
            }
            body["completion_window"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.batch.completion_window", it)
            }
            body["output_expires_after"]?.jsonObject?.let { exp ->
                exp["anchor"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.request.batch.output_expires_after.anchor", it)
                }
                exp["seconds"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.request.batch.output_expires_after.seconds", it)
                }
            }
            body["metadata"]?.jsonObject?.keys?.joinToString(",")?.let {
                span.setAttribute("tracy.request.metadata.keys", it)
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setHttpStatusCode(span, response)
        // Override gen_ai.operation.name set by setCommonResponseAttributes
        val route = detectRoute(response.url, response.requestMethod)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.batch.id", it)
        }
        body["status"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.batch.status", it)
        }
        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.batch.id", it)
        }
        body["status"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.batch.status", it)
        }
        body["created_at"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.batch.created_at", it)
        }
        body["request_counts"]?.jsonObject?.let { rc ->
            rc["total"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.batch.request_counts.total", it) }
            rc["completed"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.batch.request_counts.completed", it) }
            rc["failed"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.batch.request_counts.failed", it) }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Batches API does not use server-sent events streaming
        logger.warn { "Batches API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific batch endpoint is being called based on the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): BatchRoute {
        val segments = url.pathSegments
        val batchesIndex = segments.indexOf("batches")
        if (batchesIndex == -1) {
            logger.warn { "Failed to detect batch route. Endpoint has no `batches` path segment: ${segments.joinToString(separator = "/")}" }
            return BatchRoute.LIST
        }

        val hasBatchId = segments.size > batchesIndex + 1 &&
                segments[batchesIndex + 1].isNotBlank()
        val hasCancelSegment = hasBatchId &&
                segments.size > batchesIndex + 2 &&
                segments[batchesIndex + 2] == "cancel"

        return when {
            method == "POST" && !hasBatchId -> BatchRoute.CREATE
            method == "GET" && !hasBatchId -> BatchRoute.LIST
            method == "GET" && hasBatchId -> BatchRoute.RETRIEVE
            method == "POST" && hasBatchId && hasCancelSegment -> BatchRoute.CANCEL
            else -> {
                logger.warn { "Failed to detect batch route: $method ${segments.joinToString(separator = "/")}" }
                BatchRoute.LIST
            }
        }
    }

    /**
     * Internal enum to distinguish between different batch API routes.
     */
    internal enum class BatchRoute(val operationName: String) {
        CREATE("batches.create"),    // POST /v1/batches
        RETRIEVE("batches.retrieve"), // GET /v1/batches/{batch_id}
        CANCEL("batches.cancel"),    // POST /v1/batches/{batch_id}/cancel
        LIST("batches.list"),        // GET /v1/batches
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
