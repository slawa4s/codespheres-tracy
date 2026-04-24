/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Batches API.
 *
 * Supports:
 * - `POST /batches` — Create a batch (`batches.create`)
 * - `GET /batches/{batch_id}` — Retrieve a batch (`batches.retrieve`)
 * - `GET /batches` — List batches (`batches.list`)
 * - `POST /batches/{batch_id}/cancel` — Cancel a batch (`batches.cancel`)
 *
 * See [Batches API Reference](https://platform.openai.com/docs/api-reference/batch)
 */
internal class BatchesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        if (route == BatchRoute.CREATE) {
            val body = request.body.asJson()?.jsonObject ?: return
            body["endpoint"]?.let { span.setAttribute("gen_ai.request.batch.endpoint", it.jsonPrimitive.content) }
            body["completion_window"]?.let {
                span.setAttribute("gen_ai.request.batch.completion_window", it.jsonPrimitive.content)
            }
            body["input_file_id"]?.let {
                span.setAttribute("gen_ai.request.batch.input_file_id", it.jsonPrimitive.content)
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        val body = response.body.asJson()?.jsonObject ?: return

        if (route == BatchRoute.LIST) {
            val data = body["data"]
            if (data is JsonArray) {
                span.setAttribute("gen_ai.response.list.count", data.size.toLong())
            }
        } else {
            body["id"]?.let { span.setAttribute("gen_ai.response.batch.id", it.jsonPrimitive.content) }
            body["status"]?.let { span.setAttribute("gen_ai.response.batch.status", it.jsonPrimitive.content) }
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
        val hasBatchId = segments.size > (batchesIndex + 1) &&
                segments[batchesIndex + 1].isNotBlank()

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
