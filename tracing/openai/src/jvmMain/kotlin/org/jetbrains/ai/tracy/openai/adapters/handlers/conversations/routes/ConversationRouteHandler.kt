/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Handles requests and responses for different conversations API routes of OpenAI.
 */
internal interface ConversationRouteHandler {
    fun handleRequest(span: Span, request: TracyHttpRequest)
    fun handleResponse(span: Span, response: TracyHttpResponse)
}

/**
 * Sets the common attributes present on every conversations span.
 *
 * - `gen_ai.operation.name` — explicit operation name (overrides the value written by
 *   [OpenAIApiUtils.setCommonResponseAttributes] from `body["object"]`).
 * - `openai.api.type` — always `"conversations"`.
 */
internal fun Span.setConversationCommonAttributes(operationName: String) {
    setAttribute(GEN_AI_OPERATION_NAME, operationName)
    setAttribute("openai.api.type", "conversations")
}

/**
 * Extracts `conversation_id` from a path like `/v1/conversations/{conversation_id}` or
 * `/v1/conversations/{conversation_id}/items/{item_id}`.
 */
internal fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val conversationsIndex = segments.indexOf("conversations")
    if (conversationsIndex == -1 || segments.size <= conversationsIndex + 1) return null
    val id = segments[conversationsIndex + 1]
    return if (id.isNotBlank() && id != "conversations") id else null
}

/**
 * Extracts `item_id` from a path like `/v1/conversations/{id}/items/{item_id}`.
 */
internal fun extractItemIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val itemsIndex = segments.indexOf("items")
    if (itemsIndex == -1 || segments.size <= itemsIndex + 1) return null
    val id = segments[itemsIndex + 1]
    return if (id.isNotBlank()) id else null
}

/**
 * Traces conversation-level attributes from a response body:
 * - `gen_ai.conversation.id`
 * - `tracy.conversation.created_at`
 * - `tracy.conversation.deleted`
 */
internal fun Span.traceConversationAttributes(body: kotlinx.serialization.json.JsonObject) {
    body["id"]?.let { setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
    body["created_at"]?.jsonPrimitive?.longOrNull?.let { setAttribute("tracy.conversation.created_at", it) }
    body["deleted"]?.let { setAttribute("tracy.conversation.deleted", it.jsonPrimitive.boolean) }
}

/**
 * Traces a single conversation item's attributes:
 * - `tracy.conversation.item.id`
 * - `tracy.conversation.item.type`
 * - `tracy.conversation.item.status`
 */
internal fun Span.traceConversationItemAttributes(body: kotlinx.serialization.json.JsonObject) {
    body["id"]?.let { setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
    body["type"]?.let { setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
    body["status"]?.let { setAttribute("tracy.conversation.item.status", it.jsonPrimitive.content) }
}
