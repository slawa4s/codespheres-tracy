/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.batches.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `POST /v1/messages/batches` endpoint.
 *
 * See [batches/create](https://platform.claude.com/docs/en/api/messages/batches/create)
 */
internal class CreateBatchHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        val requests = body["requests"]
        if (requests is JsonArray) {
            span.setAttribute("gen_ai.request.requests.size", requests.size.toLong())
            for ((index, request) in requests.withIndex()) {
                val customId = request.jsonObject["custom_id"]?.jsonPrimitive?.content
                val params = request.jsonObject["params"].toString()

                span.setAttribute("gen_ai.request.requests.$index.custom_id", customId)
                span.setAttribute("gen_ai.request.requests.$index.params", params)
            }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceMessageBatch(body)
    }
}
