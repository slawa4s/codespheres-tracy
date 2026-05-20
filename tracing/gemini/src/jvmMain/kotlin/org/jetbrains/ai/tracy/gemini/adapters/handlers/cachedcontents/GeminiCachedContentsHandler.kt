/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents.routes.CreateCachedContentHandler
import org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents.routes.DeleteCachedContentHandler
import org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents.routes.GetCachedContentHandler
import org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents.routes.ListCachedContentsHandler
import org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents.routes.UpdateCachedContentHandler

/**
 * Parses Gemini Cached Contents API requests and responses.
 *
 * Dispatches to per-route [RouteHandler] implementations under `cachedcontents/routes/`:
 * | HTTP method      | `gen_ai.operation.name` |
 * |------------------|-------------------------|
 * | GET (collection) | `list`                  |
 * | GET (resource)   | `get`                   |
 * | POST             | `create`                |
 * | PATCH            | `update`                |
 * | DELETE           | `delete`                |
 *
 * See: [Gemini Caching API](https://ai.google.dev/api/caching)
 */
internal class GeminiCachedContentsHandler : EndpointApiHandler {

    private val routeHandlers: Map<CachedContentRoute, RouteHandler> by lazy {
        mapOf(
            CachedContentRoute.LIST to ListCachedContentsHandler(),
            CachedContentRoute.GET to GetCachedContentHandler(),
            CachedContentRoute.CREATE to CreateCachedContentHandler(),
            CachedContentRoute.UPDATE to UpdateCachedContentHandler(),
            CachedContentRoute.DELETE to DeleteCachedContentHandler(),
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gemini.api.type", "cachedContents")
        val route = detectRoute(request.url, request.method) ?: return
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod) ?: return
        routeHandlers[route]?.handleResponse(span, response)
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private fun detectRoute(url: TracyHttpUrl, method: String): CachedContentRoute? =
        when (method.uppercase()) {
            "GET" -> {
                // Collection URL ends with "cachedContents" → list; resource URL has an ID after it → get
                if (url.pathSegments.lastOrNull() == "cachedContents") CachedContentRoute.LIST
                else CachedContentRoute.GET
            }
            "POST" -> CachedContentRoute.CREATE
            "PATCH" -> CachedContentRoute.UPDATE
            "DELETE" -> CachedContentRoute.DELETE
            else -> null
        }

    private enum class CachedContentRoute { LIST, GET, CREATE, UPDATE, DELETE }
}
