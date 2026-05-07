/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.populateUnmappedAttributes
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handles tracing attributes for the Anthropic Message Batches API (`/v1/messages/batches`).
 *
 * Extracts telemetry from batch creation requests and batch status responses, setting
 * `gen_ai.operation.name` and batch-specific counters so that tracing backends can
 * distinguish batch operations from single-turn message calls.
 *
 * See: [Anthropic Message Batches API](https://docs.anthropic.com/en/api/creating-message-batches)
 */
internal class BatchesAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gen_ai.operation.name", "create_message_batch")

        val body = request.body.asJson()?.jsonObject ?: return

        // Extract model from the first request's params, if present
        val requests = body["requests"]
        if (requests is JsonArray && requests.isNotEmpty()) {
            requests.jsonArray.firstOrNull()
                ?.jsonObject?.get("params")
                ?.jsonObject?.get("model")
                ?.jsonPrimitive?.content
                ?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }
        }

        span.populateUnmappedAttributes(body, mappedRequestAttributes, PayloadType.REQUEST)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.response.id", it) }
        body["type"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.output.type", it) }
        body["processing_status"]?.jsonPrimitive?.content?.let {
            span.setAttribute("anthropic.batch.processing_status", it)
        }

        body["request_counts"]?.jsonObject?.let { counts ->
            counts["processing"]?.jsonPrimitive?.content?.toLongOrNull()?.let {
                span.setAttribute("anthropic.batch.request_counts.processing", it)
            }
            counts["succeeded"]?.jsonPrimitive?.content?.toLongOrNull()?.let {
                span.setAttribute("anthropic.batch.request_counts.succeeded", it)
            }
            counts["errored"]?.jsonPrimitive?.content?.toLongOrNull()?.let {
                span.setAttribute("anthropic.batch.request_counts.errored", it)
            }
            counts["canceled"]?.jsonPrimitive?.content?.toLongOrNull()?.let {
                span.setAttribute("anthropic.batch.request_counts.canceled", it)
            }
            counts["expired"]?.jsonPrimitive?.content?.toLongOrNull()?.let {
                span.setAttribute("anthropic.batch.request_counts.expired", it)
            }
        }

        span.populateUnmappedAttributes(body, mappedResponseAttributes, PayloadType.RESPONSE)
    }

    // Streaming is not applicable for the batches endpoint
    override fun handleStreaming(span: Span, events: String) = Unit

    private val mappedRequestAttributes = listOf("requests")

    private val mappedResponseAttributes = listOf(
        "id",
        "type",
        "processing_status",
        "request_counts"
    )
}
