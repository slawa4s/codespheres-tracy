/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*
import kotlinx.serialization.json.*

/**
 * Handler for the Anthropic Message Batches API (`POST /v1/messages/batches`).
 *
 * Parses batch creation request and response bodies to extract telemetry data including
 * batch size, model, processing status, and request outcome counts.
 *
 * See: [Anthropic Message Batches API](https://docs.anthropic.com/en/api/creating-message-batches)
 */
internal class AnthropicBatchApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["requests"]?.jsonArray?.let { requests ->
            span.setAttribute("gen_ai.batch.requests_count", requests.size.toLong())

            // Extract model from the first individual request params, if present
            requests.firstOrNull()?.jsonObject?.get("params")?.jsonObject?.let { params ->
                params["model"]?.jsonPrimitive?.let {
                    span.setAttribute(GEN_AI_REQUEST_MODEL, it.content)
                }
                params["max_tokens"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute(GEN_AI_REQUEST_MAX_TOKENS, it.toLong())
                }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.content) }
        body["type"]?.jsonPrimitive?.let { span.setAttribute(GEN_AI_OUTPUT_TYPE, it.content) }

        body["processing_status"]?.jsonPrimitive?.let {
            span.setAttribute("gen_ai.batch.processing_status", it.content)
        }

        // See: https://docs.anthropic.com/en/api/creating-message-batches#response-request-counts
        body["request_counts"]?.jsonObject?.let { counts ->
            counts["processing"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.batch.request_counts.processing", it.toLong())
            }
            counts["succeeded"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.batch.request_counts.succeeded", it.toLong())
            }
            counts["errored"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.batch.request_counts.errored", it.toLong())
            }
            counts["canceled"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.batch.request_counts.canceled", it.toLong())
            }
            counts["expired"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.batch.request_counts.expired", it.toLong())
            }
        }
    }

    // Batches API does not support streaming
    override fun handleStreaming(span: Span, events: String) = Unit
}
