/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.items

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput

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
 * Item fields that are *not* sensitive content and should be traced verbatim.
 * Everything else (e.g. `content`, `output`, `results`, `queries`, `arguments`,
 * `action`, `actions`) is passed through [orRedactedOutput] before being recorded.
 */
private val WELL_KNOWN_ITEM_FIELDS = setOf(
    "id", "call_id", "status", "type", "phase", "role", "namespace", "name", "object"
)

/**
 * Trace a single `ConversationItem` (RETRIEVE response) under `tracy.response.{field}`.
 */
internal fun Span.traceConversationItem(item: JsonObject) {
    traceConversationItemFields(item, prefix = "tracy.response")
}

/**
 * Trace an array of `ConversationItem` objects under `tracy.response.data.{i}.{field}`.
 */
internal fun Span.traceConversationItems(items: JsonArray) {
    for ((index, element) in items.withIndex()) {
        val item = element as? JsonObject ?: continue
        traceConversationItemFields(item, prefix = "tracy.response.data.$index")
    }
}

/**
 * Writes every field of a `ConversationItem` under `{prefix}.{field}`. Well-known
 * structural fields (see [WELL_KNOWN_ITEM_FIELDS]) are traced verbatim; everything
 * else is passed through [orRedactedOutput].
 */
private fun Span.traceConversationItemFields(item: JsonObject, prefix: String) {
    for ((key, value) in item) {
        val attrKey = "$prefix.$key"
        val raw = when (value) {
            is JsonPrimitive -> value.content
            else -> value.toString()
        }
        if (key in WELL_KNOWN_ITEM_FIELDS) {
            setAttribute(attrKey, raw)
        } else {
            setAttribute(attrKey, raw.orRedactedOutput())
        }
    }
}

/**
 * Trace a `ConversationItemList` wrapper (LIST and ITEMS_CREATE responses):
 * pagination under `tracy.response.list.{field}` and per-element items under
 * `tracy.response.data.{i}.{field}` (via [traceConversationItems]).
 */
internal fun Span.traceConversationItemList(body: JsonObject) {
    val data = body["data"]
    if (data is JsonArray) {
        setAttribute("tracy.response.list.count", data.size.toLong())
        traceConversationItems(data)
    } else {
        setAttribute("tracy.response.list.count", 0L)
    }
    body["object"]?.jsonPrimitive?.contentOrNull?.let {
        setAttribute("tracy.response.list.object", it)
    }
    body["has_more"]?.let {
        setAttribute("tracy.response.list.has_more", it.jsonPrimitive.boolean)
    }
    body["first_id"]?.jsonPrimitive?.contentOrNull?.let {
        setAttribute("tracy.response.list.first_id", it)
    }
    body["last_id"]?.jsonPrimitive?.contentOrNull?.let {
        setAttribute("tracy.response.list.last_id", it)
    }
}
