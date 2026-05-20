/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.policy.orRedactedInput

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
 * Trace OpenAI's `Conversation` object (CREATE / RETRIEVE / UPDATE responses) under
 * `tracy.response.{field}`. Also writes `gen_ai.conversation.id` from `body.id` for
 * OTel semconv compatibility.
 */
internal fun Span.traceConversation(body: JsonObject) {
    body["id"]?.jsonPrimitive?.content?.let {
        setAttribute("gen_ai.conversation.id", it)
    }
    traceConversationFields(body, prefix = "tracy.response")
}

/**
 * Trace an array of `Conversation` objects under `tracy.response.data.{i}.{field}`.
 * Does not write the top-level `gen_ai.conversation.id` — there is no single conversation.
 */
internal fun Span.traceConversations(items: JsonArray) {
    for ((index, element) in items.withIndex()) {
        val item = element as? JsonObject ?: continue
        traceConversationFields(item, prefix = "tracy.response.data.$index")
    }
}

private fun Span.traceConversationFields(body: JsonObject, prefix: String) {
    val span = this
    body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("$prefix.id", it) }
    body["created_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.created_at", it)
    }
    body["metadata"]?.let { span.setAttribute("$prefix.metadata", it.toString()) }
    body["object"]?.jsonPrimitive?.content?.let { span.setAttribute("$prefix.object", it) }
}

/**
 * Fields on a Conversations CREATE `items[]` element that are *not* sensitive
 * user content and should be traced verbatim. Everything else (e.g. `content`,
 * `output`, `results`, `queries`, `arguments`, `action`) is passed through
 * [orRedactedInput] before being recorded.
 */
private val WELL_KNOWN_ITEM_FIELDS = setOf(
    "id", "role", "status", "type", "namespace", "call_id", "name"
)

/**
 * Traces one element from a Conversations CREATE request body's `items[]` array.
 * Iterates the element's fields and writes each under `{indexPrefix}.{field}`,
 * applying [orRedactedInput] for any field not in [WELL_KNOWN_ITEM_FIELDS].
 */
internal fun Span.traceRequestConversationItem(item: JsonObject, indexPrefix: String) {
    for ((key, value) in item) {
        val attrKey = "$indexPrefix.$key"
        val raw = when (value) {
            is JsonPrimitive -> value.content
            else -> value.toString()
        }
        if (key in WELL_KNOWN_ITEM_FIELDS) {
            setAttribute(attrKey, raw)
        } else {
            setAttribute(attrKey, raw.orRedactedInput())
        }
    }
}
