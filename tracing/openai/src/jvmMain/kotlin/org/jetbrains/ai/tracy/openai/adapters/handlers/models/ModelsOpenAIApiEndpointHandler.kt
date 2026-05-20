/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.models

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.openai.adapters.handlers.models.routes.ListModelsHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.models.routes.RetrieveModelHandler

/**
 * Handler for OpenAI Models API endpoints.
 *
 * Dispatches to per-route [RouteHandler] implementations under `models/routes/`:
 * 1. `GET /v1/models`              → `"models.list"`
 * 2. `GET /v1/models/{model_id}`   → `"models.retrieve"`
 *
 * See [Models API Reference](https://platform.openai.com/docs/api-reference/models)
 */
internal class ModelsOpenAIApiEndpointHandler : EndpointApiHandler {

    private val routeHandlers: Map<ModelRoute, RouteHandler> by lazy {
        mapOf(
            ModelRoute.LIST to ListModelsHandler(),
            ModelRoute.RETRIEVE to RetrieveModelHandler(),
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url)
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url)
        routeHandlers[route]?.handleResponse(span, response)
    }

    override fun handleStreaming(span: Span, events: String) {
        // Models API does not use SSE streaming
    }

    private fun detectRoute(url: TracyHttpUrl): ModelRoute {
        val segments = url.pathSegments
        val modelsIndex = segments.indexOf("models")
        val hasModelId = modelsIndex != -1 &&
                segments.size > modelsIndex + 1 &&
                segments[modelsIndex + 1].isNotBlank()
        return if (hasModelId) ModelRoute.RETRIEVE else ModelRoute.LIST
    }

    private enum class ModelRoute { LIST, RETRIEVE }
}
