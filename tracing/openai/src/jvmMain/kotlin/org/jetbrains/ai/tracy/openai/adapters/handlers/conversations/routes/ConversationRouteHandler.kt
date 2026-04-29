/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Handles requests and responses for different conversation API routes of OpenAI.
 */
internal interface ConversationRouteHandler {
    fun handleRequest(span: Span, request: TracyHttpRequest)
    fun handleResponse(span: Span, response: TracyHttpResponse)
}

/**
 * Extracts `conversation_id` from a path like `/v1/conversations/{conversation_id}`.
 */
internal fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val conversationsIndex = segments.indexOf("conversations")
    return if (conversationsIndex != -1 && segments.size > conversationsIndex + 1) {
        val potentialId = segments[conversationsIndex + 1]
        if (potentialId.isNotBlank() && potentialId != "items") potentialId else null
    } else {
        null
    }
}

/**
 * Extracts `item_id` from a path like `/v1/conversations/{conversation_id}/items/{item_id}`.
 */
internal fun extractItemIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val itemsIndex = segments.indexOf("items")
    return if (itemsIndex != -1 && segments.size > itemsIndex + 1) {
        val potentialId = segments[itemsIndex + 1]
        if (potentialId.isNotBlank()) potentialId else null
    } else {
        null
    }
}

/**
 * Sets conversation items response attributes shared by ITEMS_CREATE and ITEMS_LIST routes:
 * `tracy.conversation.items.count` (data array size), `tracy.conversation.items.first_id`,
 * and `tracy.conversation.items.last_id`.
 */
internal fun Span.setConversationItemsResponseAttributes(body: JsonObject) {
    val data = body["data"]
    if (data is JsonArray) {
        setAttribute("tracy.conversation.items.count", data.size.toLong())
    }
    body["first_id"]?.let { setAttribute("tracy.conversation.items.first_id", it.jsonPrimitive.content) }
    body["last_id"]?.let { setAttribute("tracy.conversation.items.last_id", it.jsonPrimitive.content) }
}
