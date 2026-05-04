/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

/**
 * Minimal handler for Gemini Cached Content API requests (e.g. POST /v1beta/cachedContents).
 *
 * These requests manage cached content resources and are not content-generation calls,
 * so no special attribute extraction is performed beyond what the adapter sets.
 *
 * See: [Gemini Context Caching](https://ai.google.dev/gemini-api/docs/caching)
 */
internal class GeminiCachedContentsHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) = Unit
    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) = Unit
    override fun handleStreaming(span: Span, events: String) = Unit
}
