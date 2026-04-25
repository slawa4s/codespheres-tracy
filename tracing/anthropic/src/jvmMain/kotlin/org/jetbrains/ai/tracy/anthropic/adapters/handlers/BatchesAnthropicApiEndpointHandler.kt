/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
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
 * Supports:
 * - `POST /messages/batches` — Create a batch (`batches.create`)
 * - `GET /messages/batches/{message_batch_id}` — Retrieve a batch (`batches.retrieve`)
 * - `GET /messages/batches` — List batches (`batches.list`)
 * - `POST /messages/batches/{message_batch_id}/cancel` — Cancel a batch (`batches.cancel`)
 *
 * See [Message Batches API Reference](https://docs.anthropic.com/en/api/creating-message-batches)
 */
internal class BatchesAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
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
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.let { span.setAttribute("gen_ai.response.batch.id", it.jsonPrimitive.content) }
        body["processing_status"]?.let {
            span.setAttribute("gen_ai.response.batch.processing_status", it.jsonPrimitive.content)
        }
        body["created_at"]?.let { span.setAttribute("gen_ai.response.batch.created_at", it.jsonPrimitive.content) }
        body["expires_at"]?.let { span.setAttribute("gen_ai.response.batch.expires_at", it.jsonPrimitive.content) }

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
        // Batches API does not use SSE streaming
    }

    private fun detectRoute(url: TracyHttpUrl, method: String): BatchRoute {
        val segments = url.pathSegments
        val batchesIndex = segments.indexOf("batches")
        if (batchesIndex == -1) {
            logger.warn { "Failed to detect batch route. Endpoint has no `batches` path segment: ${segments.joinToString(separator = "/")}" }
            return BatchRoute.CREATE
        }
        val hasBatchId = segments.size > (batchesIndex + 1) && segments[batchesIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasBatchId -> BatchRoute.CREATE
            method == "POST" && hasBatchId && segments.contains("cancel") -> BatchRoute.CANCEL
            method == "GET" && hasBatchId -> BatchRoute.RETRIEVE
            method == "GET" && !hasBatchId -> BatchRoute.LIST
            else -> {
                logger.warn { "Failed to detect batch route: $method ${segments.joinToString(separator = "/")}" }
                BatchRoute.CREATE
            }
        }
    }

    private enum class BatchRoute(val operationName: String) {
        CREATE("batches.create"),
        RETRIEVE("batches.retrieve"),
        LIST("batches.list"),
        CANCEL("batches.cancel")
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
