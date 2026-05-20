/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `POST /conversations` endpoint.
 *
 * See [/conversations/methods/create](https://developers.openai.com/api/reference/resources/conversations/methods/create)
 */
internal class CreateConversationHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["metadata"]?.let {
            span.setAttribute("tracy.request.metadata", it.toString())
        }
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
        span.traceConversation(body)
    }
}
