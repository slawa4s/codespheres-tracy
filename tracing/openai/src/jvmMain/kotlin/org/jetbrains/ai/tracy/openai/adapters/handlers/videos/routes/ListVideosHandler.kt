/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.videos.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `GET /videos` endpoint.
 */
internal class ListVideosHandler : RouteHandler {
    /**
     * Request: Query parameters after, limit, order
     */
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val params = request.url.parameters
        params.queryParameter("after")?.let { span.setAttribute("gen_ai.request.after", it) }
        params.queryParameter("limit")?.toLongOrNull()?.let { span.setAttribute("tracy.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
    }

    /**
     * Response: { data: Video[], first_id, last_id, has_more, object }
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "videos.list")

        val body = response.body.asJson()?.jsonObject ?: return

        body["object"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.object", it) }
        body["first_id"]?.let { span.setAttribute("gen_ai.response.first_id", it.jsonPrimitive.content) }
        body["last_id"]?.let { span.setAttribute("gen_ai.response.last_id", it.jsonPrimitive.content) }
        body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.has_more", it) }

        val data = body["data"]
        if (data != null && data is JsonArray) {
            span.setAttribute("gen_ai.response.videos_count", data.size.toLong())
            for ((index, videoElement) in data.withIndex()) {
                if (videoElement is JsonObject) {
                    span.traceVideoModel(videoElement, "gen_ai.response.videos.$index")
                }
            }
        } else {
            span.setAttribute("gen_ai.response.videos_count", 0L)
        }
    }
}