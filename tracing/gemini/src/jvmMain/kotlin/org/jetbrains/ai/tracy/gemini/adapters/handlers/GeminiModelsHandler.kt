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
 * Minimal handler for Gemini Models API requests (e.g. GET /v1beta/models/{name}).
 *
 * These requests have no `:operation` suffix in the URL path, so they are not
 * content-generation calls and require no special attribute extraction.
 *
 * See: [Gemini Models API](https://ai.google.dev/api/models)
 */
internal class GeminiModelsHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) = Unit
    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) = Unit
    override fun handleStreaming(span: Span, events: String) = Unit
}
