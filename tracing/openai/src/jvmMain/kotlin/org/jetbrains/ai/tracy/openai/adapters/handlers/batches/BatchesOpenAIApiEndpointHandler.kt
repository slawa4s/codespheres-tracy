/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.intOrNull
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
 * Endpoint handler for the OpenAI Batch API (`/v1/batches`).
 *
 * Covers four operations detected from the HTTP method and URL path:
 * - `batches.create`   — POST `/v1/batches`
 * - `batches.list`     — GET  `/v1/batches`
 * - `batches.retrieve` — GET  `/v1/batches/{batch_id}`
 * - `batches.cancel`   — POST `/v1/batches/{batch_id}/cancel`
 *
 * For every route the handler sets:
 * - `openai.api.type = "batches"`
 * - `gen_ai.operation.name` — operation-specific value, overriding the `"batch"` written by
 *   [org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils.setCommonResponseAttributes]
 *   via `body["object"]`
 *
 * For `batches.create` requests the following additional attributes are recorded:
 * - `tracy.request.batch.endpoint`
 * - `tracy.request.batch.completion_window`
 * - `tracy.request.batch.output_expires_after.anchor`
 * - `tracy.request.batch.output_expires_after.seconds`
 * - `tracy.request.batch.input_file.id`
 * - `tracy.request.metadata.keys` — comma-joined sorted keys of the `metadata` map
 *
 * Response attributes (all operations):
 * - `tracy.batch.id`
 * - `tracy.batch.status`
 * - `tracy.batch.created_at`
 * - `tracy.batch.request_counts.total`
 * - `tracy.batch.request_counts.completed`
 * - `tracy.batch.request_counts.failed`
 *
 * See: [OpenAI Batch API](https://platform.openai.com/docs/api-reference/batch)
 */
internal class BatchesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operation = detectOperation(request.url.pathSegments, request.method)
        span.setAttribute("openai.api.type", "batches")
        span.setAttribute("gen_ai.operation.name", operation)

        if (operation == "batches.create") {
            val body = request.body.asJson()?.jsonObject ?: return
            body["endpoint"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.batch.endpoint", it)
            }
            body["completion_window"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.batch.completion_window", it)
            }
            body["output_expires_after"]?.jsonObject?.let { expires ->
                expires["anchor"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.request.batch.output_expires_after.anchor", it)
                }
                expires["seconds"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("tracy.request.batch.output_expires_after.seconds", it.toLong())
                }
            }
            body["input_file_id"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.batch.input_file.id", it)
            }
            body["metadata"]?.jsonObject?.keys?.sorted()?.joinToString(",")?.let { keys ->
                if (keys.isNotEmpty()) span.setAttribute("tracy.request.metadata.keys", keys)
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val operation = detectOperation(response.url.pathSegments, response.requestMethod)
        // Override gen_ai.operation.name that setCommonResponseAttributes may have set via body["object"]
        span.setAttribute("gen_ai.operation.name", operation)

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
        body["request_counts"]?.jsonObject?.let { counts ->
            counts["total"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("tracy.batch.request_counts.total", it.toLong())
            }
            counts["completed"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("tracy.batch.request_counts.completed", it.toLong())
            }
            counts["failed"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("tracy.batch.request_counts.failed", it.toLong())
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private fun detectOperation(pathSegments: List<String>, method: String): String {
        val batchesIdx = pathSegments.indexOf("batches")
        val afterBatches = if (batchesIdx >= 0) pathSegments.drop(batchesIdx + 1) else emptyList()
        return when {
            afterBatches.lastOrNull() == "cancel" -> "batches.cancel"
            method.uppercase() == "POST" && afterBatches.isEmpty() -> "batches.create"
            method.uppercase() == "GET" && afterBatches.isEmpty() -> "batches.list"
            else -> "batches.retrieve"
        }
    }

    companion object {
        @Suppress("unused")
        private val logger = KotlinLogging.logger {}
    }
}
