/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `GET /batches` endpoint.
 */
internal class ListBatchesHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val params = request.url.parameters
        params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("tracy.response.list.count", data.size.toLong())
            span.traceBatches(data)
        }
        body["object"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.list.object", it)
        }
        body["has_more"]?.jsonPrimitive?.let {
            span.setAttribute("tracy.response.list.has_more", it.content)
        }
        body["first_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.list.first_id", it)
        }
        body["last_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.list.last_id", it)
        }
    }
}
