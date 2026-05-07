/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic Message Batches API (`/v1/messages/batches*`).
 *
 * Extracts telemetry attributes from batch create/retrieve/list/cancel requests
 * and their responses.
 *
 * See: [Anthropic Message Batches API](https://docs.anthropic.com/en/api/creating-message-batches)
 */
internal class BatchesAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["requests"]?.let {
            if (it is JsonArray) {
                span.setAttribute("gen_ai.batch.requests.count", it.size.toLong())
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.batch.id", it)
        }
        body["processing_status"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.batch.processing_status", it)
        }
        body["request_counts"]?.let {
            if (it is JsonObject) {
                it["processing"]?.jsonPrimitive?.longOrNull?.let { v ->
                    span.setAttribute("gen_ai.batch.request_counts.processing", v)
                }
                it["succeeded"]?.jsonPrimitive?.longOrNull?.let { v ->
                    span.setAttribute("gen_ai.batch.request_counts.succeeded", v)
                }
                it["errored"]?.jsonPrimitive?.longOrNull?.let { v ->
                    span.setAttribute("gen_ai.batch.request_counts.errored", v)
                }
                it["canceled"]?.jsonPrimitive?.longOrNull?.let { v ->
                    span.setAttribute("gen_ai.batch.request_counts.canceled", v)
                }
                it["expired"]?.jsonPrimitive?.longOrNull?.let { v ->
                    span.setAttribute("gen_ai.batch.request_counts.expired", v)
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
