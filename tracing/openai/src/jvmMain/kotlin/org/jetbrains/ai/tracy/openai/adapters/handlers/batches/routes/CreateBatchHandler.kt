/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `POST /batches` endpoint.
 */
internal class CreateBatchHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        body["input_file_id"]?.let {
            span.setAttribute("tracy.request.input_file_id", it.jsonPrimitive.content)
        }
        body["endpoint"]?.let {
            span.setAttribute("tracy.request.endpoint", it.jsonPrimitive.content)
        }
        body["completion_window"]?.let {
            span.setAttribute("tracy.request.completion_window", it.jsonPrimitive.content)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceOpenAIBatchObject(body)
    }
}
