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
 * Handler for Gemini Embeddings API requests
 * (e.g. POST /v1beta/models/text-embedding-004:embedContent).
 *
 * Embedding endpoints are identified either by model names containing "embed"
 * (case-insensitive) or by the `embedContent` operation name.
 *
 * See: [Gemini Embeddings API](https://ai.google.dev/api/embeddings)
 */
internal class GeminiEmbeddingsHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) = Unit
    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) = Unit
    override fun handleStreaming(span: Span, events: String) = Unit
}
