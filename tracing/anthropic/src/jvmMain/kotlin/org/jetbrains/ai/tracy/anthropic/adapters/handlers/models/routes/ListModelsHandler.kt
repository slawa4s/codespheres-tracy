/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.models.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `GET /v1/models` endpoint.
 *
 * See [models/list](https://platform.claude.com/docs/en/api/models-list)
 */
internal class ListModelsHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // NOTE: No request-side body attributes, only query parameters
        val params = request.url.parameters
        params.queryParameter("after_id")?.let {
            span.setAttribute("gen_ai.request.after_id", it)
        }
        params.queryParameter("before_id")?.let {
            span.setAttribute("gen_ai.request.before_id", it)
        }
        params.queryParameter("limit")?.toLongOrNull()?.let {
            span.setAttribute("gen_ai.request.limit", it)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("gen_ai.response.list.count", data.size.toLong())
            span.traceBetaModelInfo(data)
        }
        body["has_more"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.list.has_more", it)
        }
        body["first_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.list.first_id", it)
        }
        body["last_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.list.last_id", it)
        }
    }
}
