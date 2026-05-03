/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Gemini Cached Contents API (context caching) list responses.
 *
 * Parses the `caches` array from the response body and extracts:
 * - `gen_ai.response.list.count` — number of entries in the `caches` array
 * - `gen_ai.response.list.has_more` — `true` when a `nextPageToken` field is present and non-empty,
 *   `false` otherwise
 *
 * See: [Context Caching API](https://ai.google.dev/gemini-api/docs/caching)
 */
internal class GeminiCachedContentsHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) = Unit

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["caches"]?.jsonArray?.size?.let {
            span.setAttribute("gen_ai.response.list.count", it.toLong())
        }

        val nextPageToken = body["nextPageToken"]?.jsonPrimitive?.content
        span.setAttribute("gen_ai.response.list.has_more", !nextPageToken.isNullOrEmpty())
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
