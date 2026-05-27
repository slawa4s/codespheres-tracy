/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents.CachedContentTracer

/**
 * Handles the `PATCH /v1beta/cachedContents/{name}` endpoint.
 *
 * The PATCH body is a (possibly partial) [CachedContent] resource — the API documents only
 * `expiration` as updatable, but the request body still validates against the full
 * `CachedContent` schema. The shared [CachedContentTracer] handles missing fields gracefully.
 *
 * Also captures the `updateMask` query parameter, which lists the comma-separated fully-
 * qualified field names being modified (e.g. `"expireTime"`).
 *
 * See: [Gemini Caching API — patch](https://ai.google.dev/api/caching#method:-cachedcontents.patch)
 */
internal class PatchCachedContentHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "patch")

        request.url.parameters.queryParameter("updateMask")?.let {
            span.setAttribute("tracy.request.update_mask", it)
        }

        request.body.asJson()?.jsonObject?.let { body ->
            CachedContentTracer.traceRequest(span, body)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        response.body.asJson()?.jsonObject?.let { body ->
            CachedContentTracer.traceResponse(span, body)
        }
    }
}
