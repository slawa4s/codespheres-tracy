/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles `GET /conversations/{conversation_id}/items` — list items in a conversation.
 */
internal class ListItemsHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("gen_ai.request.conversation.requested_id", it)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("gen_ai.response.items_count", data.jsonArray.size.toLong())
            for ((index, item) in data.jsonArray.withIndex()) {
                item.jsonObject["id"]?.let {
                    span.setAttribute("gen_ai.response.items.$index.id", it.jsonPrimitive.content)
                }
                item.jsonObject["type"]?.let {
                    span.setAttribute("gen_ai.response.items.$index.type", it.jsonPrimitive.content)
                }
            }
        }

        body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("gen_ai.response.has_more", it)
        }

        span.setAttribute("gen_ai.operation.name", "list_items")
    }
}
