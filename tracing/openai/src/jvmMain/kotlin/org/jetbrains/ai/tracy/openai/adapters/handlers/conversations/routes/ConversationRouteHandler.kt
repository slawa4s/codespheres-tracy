/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
 * Extracts `conversation_id` from a path like `/v1/realtime/conversations/{conversation_id}`.
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
 * Extracts `item_id` from a path like `/v1/realtime/conversations/{conversation_id}/items/{item_id}`.
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
 * Sets pagination list attributes from a response body that contains a `data` array,
 * `first_id`, `last_id`, and `has_more` fields.
 *
 * Emits:
 * - `tracy.conversation.items.count` — size of the data array
 * - `tracy.conversation.items.first_id`
 * - `tracy.conversation.items.last_id`
 * - `tracy.conversation.items.has_more`
 */
internal fun Span.setListResponseAttributes(body: JsonObject) {
    val data = body["data"]
    val count = if (data is JsonArray) data.size else 0
    setAttribute("tracy.conversation.items.count", count.toLong())

    body["first_id"]?.jsonPrimitive?.content?.let { setAttribute("tracy.conversation.items.first_id", it) }
    body["last_id"]?.jsonPrimitive?.content?.let { setAttribute("tracy.conversation.items.last_id", it) }
    body["has_more"]?.jsonPrimitive?.boolean?.let { setAttribute("tracy.conversation.items.has_more", it) }
}

/**
 * Sets attributes for a single conversation item (conversation or item object).
 *
 * Emits:
 * - `tracy.conversation.item.id`
 * - `tracy.conversation.item.type`
 * - `tracy.conversation.item.status`
 */
internal fun Span.setSingleItemAttributes(body: JsonObject) {
    body["id"]?.jsonPrimitive?.content?.let { setAttribute("tracy.conversation.item.id", it) }
    body["type"]?.jsonPrimitive?.content?.let { setAttribute("tracy.conversation.item.type", it) }
    body["status"]?.jsonPrimitive?.content?.let { setAttribute("tracy.conversation.item.status", it) }
}

/**
 * Sets pagination query parameter attributes from the request URL.
 *
 * Emits:
 * - `tracy.request.limit`
 * - `tracy.request.order`
 * - `tracy.request.after`
 */
internal fun Span.setListRequestAttributes(url: TracyHttpUrl) {
    val params = url.parameters
    params.queryParameter("limit")?.let { setAttribute("tracy.request.limit", it) }
    params.queryParameter("order")?.let { setAttribute("tracy.request.order", it) }
    params.queryParameter("after")?.let { setAttribute("tracy.request.after", it) }
}
