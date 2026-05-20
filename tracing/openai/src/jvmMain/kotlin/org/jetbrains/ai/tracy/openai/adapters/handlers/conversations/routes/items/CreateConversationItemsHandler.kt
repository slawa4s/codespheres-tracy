/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.items

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.extractConversationIdFromPath
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.traceRequestConversationItem

/**
 * Handles the `POST /conversations/{conversation_id}/items` endpoint.
 */
internal class CreateConversationItemsHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("tracy.request.conversation_id", it)
        }

        val include = request.url.parameters.queryParameterValues("include").filterNotNull()
        if (include.isNotEmpty()) {
            span.setAttribute("tracy.request.include", include.joinToString(","))
        }

        val body = request.body.asJson()?.jsonObject ?: return
        (body["items"] as? JsonArray)?.let { items ->
            span.setAttribute("tracy.request.items.count", items.size.toLong())
            for ((index, element) in items.withIndex()) {
                val item = element as? JsonObject ?: continue
                span.traceRequestConversationItem(item, indexPrefix = "tracy.request.items.$index")
            }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceConversationItemList(body)
    }
}
