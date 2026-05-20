/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `GET /batches/{batch_id}` endpoint.
 */
internal class RetrieveBatchHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractBatchIdFromPath(request.url)?.let { span.setAttribute("tracy.request.batch_id", it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceBatch(body)
    }
}
