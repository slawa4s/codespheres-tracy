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
 * Handles the `POST /v1beta/cachedContents` endpoint.
 *
 * Both the request and response bodies are full `CachedContent` resources; both sides are
 * traced via the shared [CachedContentTracer].
 *
 * See: [Gemini Caching API — create](https://ai.google.dev/api/caching#method:-cachedcontents.create)
 */
internal class CreateCachedContentHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "create")
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
