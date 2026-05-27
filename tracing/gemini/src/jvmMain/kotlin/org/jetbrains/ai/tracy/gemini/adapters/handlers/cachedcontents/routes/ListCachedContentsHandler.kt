/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents.CachedContentTracer

/**
 * Handles the `GET /v1beta/cachedContents` endpoint.
 *
 * Response carries `cachedContents[]` plus an optional `nextPageToken`. Per-item metadata
 * (name, model, displayName) is traced via [CachedContentTracer.traceListItem] — full
 * resource tracing per item would flood the span; backends can `GET /v1beta/{name}` for
 * detail.
 *
 * See: [Gemini Caching API — list](https://ai.google.dev/api/caching#method:-cachedcontents.list)
 */
internal class ListCachedContentsHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "list")
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        val cachedContents = body["cachedContents"]?.jsonArray ?: return
        span.setAttribute("gen_ai.response.list.count", cachedContents.size.toLong())

        val nextPageToken = body["nextPageToken"]?.jsonPrimitive?.content
        val hasMore = !nextPageToken.isNullOrEmpty()
        span.setAttribute("gen_ai.response.list.has_more", hasMore.toString())

        for ((index, item) in cachedContents.withIndex()) {
            CachedContentTracer.traceListItem(span, item.jsonObject, index)
        }
    }
}
