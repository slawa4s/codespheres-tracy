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
        body["completion_window"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.completion_window", it)
        }
        body["endpoint"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.endpoint", it)
        }
        body["input_file_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.input_file_id", it)
        }
        body["metadata"]?.let {
            span.setAttribute("tracy.request.metadata", it.toString())
        }
        body["output_expires_after"]?.let {
            span.setAttribute("tracy.request.output_expires_after", it.toString())
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceBatch(body)
    }
}
