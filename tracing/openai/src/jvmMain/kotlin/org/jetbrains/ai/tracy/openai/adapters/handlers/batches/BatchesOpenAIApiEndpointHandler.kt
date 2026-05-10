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
 * Handles the OpenAI Batches API (`/v1/batches/...`).
 *
 * Routes the four Batches endpoints and sets `openai.api.type = "batches"` and
 * the correct `gen_ai.operation.name` on every span.
 *
 * Supported routes:
 * 1. `POST /batches` → `batches.create`
 * 2. `GET /batches/{id}` → `batches.retrieve`
 * 3. `POST /batches/{id}/cancel` → `batches.cancel`
 * 4. `GET /batches` → `batches.list`
 *
 * See [OpenAI Batches API Reference](https://platform.openai.com/docs/api-reference/batch)
 */
internal class BatchesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        span.setAttribute("openai.api.type", "batches")
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        if (route == BatchesRoute.CREATE) {
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
            body["metadata"]?.jsonObject?.let { metadata ->
                val keys = metadata.keys.sorted().joinToString(",")
                if (keys.isNotEmpty()) {
                    span.setAttribute("tracy.request.metadata.keys", keys)
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        span.setAttribute("openai.api.type", "batches")
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        if (route == BatchesRoute.LIST) return

        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.batch.id", it) }
        body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.batch.status", it) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.batch.created_at", it) }
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

    override fun handleStreaming(span: Span, events: String) {
        // Batches API does not use SSE streaming
    }

    private fun detectRoute(url: TracyHttpUrl, method: String): BatchesRoute {
        val segments = url.pathSegments
        val batchIdx = segments.indexOf("batches")
        if (batchIdx == -1) {
            logger.warn { "No 'batches' segment in URL: ${segments.joinToString("/")}" }
            return BatchesRoute.LIST
        }

        val hasId = segments.size > batchIdx + 1 && segments[batchIdx + 1].isNotBlank()
        val lastSegment = segments.lastOrNull()

        return when {
            method == "POST" && !hasId -> BatchesRoute.CREATE
            method == "GET" && !hasId -> BatchesRoute.LIST
            method == "GET" && hasId -> BatchesRoute.RETRIEVE
            method == "POST" && hasId && lastSegment == "cancel" -> BatchesRoute.CANCEL
            else -> {
                logger.warn { "Unrecognised Batches route: $method ${segments.joinToString("/")}" }
                BatchesRoute.LIST
            }
        }
    }

    private enum class BatchesRoute(val operationName: String) {
        CREATE("batches.create"),
        RETRIEVE("batches.retrieve"),
        CANCEL("batches.cancel"),
        LIST("batches.list"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
