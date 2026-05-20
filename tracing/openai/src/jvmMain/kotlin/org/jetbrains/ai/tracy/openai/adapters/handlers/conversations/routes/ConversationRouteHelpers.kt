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
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Extracts the conversation id from a path like `/v1/conversations/{conversation_id}` or
 * `/v1/conversations/{conversation_id}/items/...`. Returns `null` when absent.
 */
internal fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val index = segments.indexOf("conversations")
    if (index == -1 || segments.size <= index + 1) return null
    return segments[index + 1].takeIf { it.isNotBlank() }
}

/**
 * Extracts the item id from a path like `/v1/conversations/{id}/items/{item_id}`.
 * Returns `null` when absent.
 */
internal fun extractItemIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val itemsIndex = segments.indexOf("items")
    if (itemsIndex == -1 || segments.size <= itemsIndex + 1) return null
    return segments[itemsIndex + 1].takeIf { it.isNotBlank() }
}

/**
 * Sets the pagination attributes for a conversation items list response:
 * `tracy.conversation.items.count`, `first_id`, `last_id`, `has_more`.
 */
internal fun Span.traceConversationItemsList(body: JsonObject) {
    val span = this
    val data = body["data"]
    if (data is JsonArray) {
        span.setAttribute("tracy.conversation.items.count", data.size.toLong())
    } else {
        span.setAttribute("tracy.conversation.items.count", 0L)
    }
    body["first_id"]?.let {
        span.setAttribute("tracy.conversation.items.first_id", it.jsonPrimitive.content)
    }
    body["last_id"]?.let {
        span.setAttribute("tracy.conversation.items.last_id", it.jsonPrimitive.content)
    }
    body["has_more"]?.let {
        span.setAttribute("tracy.conversation.items.has_more", it.jsonPrimitive.boolean)
    }
}

/**
 * Sets the `gen_ai.conversation.id` and `tracy.conversation.created_at` attributes
 * from the response body of CREATE / RETRIEVE / UPDATE.
 */
internal fun Span.traceConversationObject(body: JsonObject) {
    val span = this
    body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
    body["created_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("tracy.conversation.created_at", it)
    }
}
