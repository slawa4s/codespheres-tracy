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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for Anthropic Message Batches API.
 *
 * Extracts tracing attributes from batch API requests (`POST /v1/messages/batches`)
 * and responses, emitting `anthropic.api.type: batches` and `gen_ai.response.batch.*`
 * attributes instead of the Messages API attributes that the main adapter would otherwise apply.
 *
 * See: [Anthropic Message Batches API](https://docs.anthropic.com/en/api/creating-message-batches)
 */
internal class AnthropicBatchesEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "batches")

        val body = request.body.asJson()?.jsonObject ?: return

        body["requests"]?.let { requests ->
            if (requests is JsonArray) {
                span.setAttribute("gen_ai.request.batch.size", requests.size.toLong())
            }
        }

        span.populateUnmappedAttributes(body, mappedRequestAttributes, PayloadType.REQUEST)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.batch.id", it)
        }
        body["processing_status"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.batch.processing_status", it)
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

        span.populateUnmappedAttributes(body, mappedResponseAttributes, PayloadType.RESPONSE)
    }

    // streaming is not applicable to the batches endpoint
    override fun handleStreaming(span: Span, events: String) = Unit

    // https://docs.anthropic.com/en/api/creating-message-batches
    private val mappedRequestAttributes: List<String> = listOf(
        "requests"
    )

    private val mappedResponseAttributes: List<String> = listOf(
        "id",
        "processing_status",
        "request_counts"
    )
}
