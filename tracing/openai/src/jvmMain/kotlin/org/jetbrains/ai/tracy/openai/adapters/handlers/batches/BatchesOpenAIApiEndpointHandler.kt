/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
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
 * Handler for the OpenAI Batches API (`/v1/batches`).
 *
 * Supports three operations derived from URL path segments and HTTP method:
 * - `POST /v1/batches` → `gen_ai.operation.name = batches.create`
 * - `GET /v1/batches/{batch_id}` → `gen_ai.operation.name = batches.retrieve`
 * - `POST /v1/batches/{batch_id}/cancel` → `gen_ai.operation.name = batches.cancel`
 *
 * Sets `openai.api.type = "batches"` on every span and emits the following attributes:
 *
 * **Request (create only)**:
 * - `tracy.request.batch.input_file.id` — the `input_file_id` field
 * - `tracy.request.batch.endpoint` — the `endpoint` field (e.g. `/v1/chat/completions`)
 * - `tracy.request.batch.completion_window` — the `completion_window` field
 * - `tracy.request.batch.output_expires_after.anchor` — from `output_expires_after.anchor`
 * - `tracy.request.batch.output_expires_after.seconds` — from `output_expires_after.seconds`
 * - `tracy.request.metadata.keys` — comma-joined keys from the `metadata` object
 *
 * **Response** (all operations that return a batch object):
 * - `tracy.batch.id` — the batch `id`
 * - `tracy.batch.status` — the batch `status`
 * - `tracy.batch.created_at` — Unix epoch timestamp from `created_at`
 * - `tracy.batch.request_counts.total` — total request count
 * - `tracy.batch.request_counts.completed` — completed request count
 * - `tracy.batch.request_counts.failed` — failed request count
 *
 * See [Batches API Reference](https://platform.openai.com/docs/api-reference/batch)
 */
internal class BatchesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "batches")
        val operation = detectOperation(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        if (operation == OP_CREATE) {
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
            body["output_expires_after"]?.jsonObject?.let { oea ->
                oea["anchor"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.request.batch.output_expires_after.anchor", it)
                }
                oea["seconds"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("tracy.request.batch.output_expires_after.seconds", it.toLong())
                }
            }
            body["metadata"]?.jsonObject?.let { meta ->
                val keys = meta.keys.joinToString(",")
                if (keys.isNotEmpty()) span.setAttribute("tracy.request.metadata.keys", keys)
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute("openai.api.type", "batches")
        val operation = detectOperation(response.url, response.requestMethod)
        // Override the value written by setCommonResponseAttributes (which reads body["object"] = "batch")
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.batch.id", it) }
        body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.batch.status", it) }
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

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Batches API does not use server-sent events streaming" }
    }

    /**
     * Derives the operation name from URL path segments and HTTP method.
     *
     * - `POST` with no batch-id segment after `batches` → `batches.create`
     * - `POST` with a `cancel` segment → `batches.cancel`
     * - `GET` with a batch-id segment → `batches.retrieve`
     */
    internal fun detectOperation(url: TracyHttpUrl, method: String): String {
        val segments = url.pathSegments
        val batchesIndex = segments.indexOf("batches")
        if (batchesIndex == -1) {
            logger.warn { "No 'batches' path segment found: ${segments.joinToString("/")}" }
            return OP_CREATE
        }
        val hasBatchId = segments.size > batchesIndex + 1 && segments[batchesIndex + 1].isNotBlank()
        val hasCancel = hasBatchId && segments.contains("cancel")

        return when {
            method == "POST" && !hasBatchId -> OP_CREATE
            method == "POST" && hasCancel -> OP_CANCEL
            method == "GET" && hasBatchId -> OP_RETRIEVE
            else -> {
                logger.warn { "Unknown batches operation: $method ${segments.joinToString("/")}" }
                OP_CREATE
            }
        }
    }

    companion object {
        private const val OP_CREATE = "batches.create"
        private const val OP_RETRIEVE = "batches.retrieve"
        private const val OP_CANCEL = "batches.cancel"
        private val logger = KotlinLogging.logger {}
    }
}
