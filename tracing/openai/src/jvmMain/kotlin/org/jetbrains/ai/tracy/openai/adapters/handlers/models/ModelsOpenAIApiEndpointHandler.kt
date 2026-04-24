/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.models

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

/**
 * Handler for OpenAI Models API.
 *
 * Supports:
 * - `GET /models` — List models (`models.list`)
 * - `GET /models/{model}` — Retrieve a model (`models.retrieve`)
 *
 * See [Models API Reference](https://platform.openai.com/docs/api-reference/models)
 */
internal class ModelsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val segments = request.url.pathSegments
        val modelsIndex = segments.indexOf("models")
        if (modelsIndex != -1 && segments.size > modelsIndex + 1 && segments[modelsIndex + 1].isNotBlank()) {
            val modelId = segments[modelsIndex + 1]
            span.setAttribute(GEN_AI_OPERATION_NAME, "models.retrieve")
            span.setAttribute(GEN_AI_REQUEST_MODEL, modelId)
        } else {
            span.setAttribute(GEN_AI_OPERATION_NAME, "models.list")
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // No additional response attributes for Models API
    }

    override fun handleStreaming(span: Span, events: String) {
        // Models API does not use SSE streaming
    }
}
