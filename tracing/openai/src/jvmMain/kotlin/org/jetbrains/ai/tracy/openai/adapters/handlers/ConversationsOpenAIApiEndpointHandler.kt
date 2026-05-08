/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Conversations API and Conversation Items sub-resource.
 *
 * Handles CRUD operations on `/v1/conversations` and `/v1/conversations/{id}/items`.
 *
 * See: [Conversations API](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        val convoIdx = segments.indexOf("conversations")
        val hasConvoId = convoIdx >= 0 && segments.size > convoIdx + 1
        val hasItems = hasConvoId && segments.contains("items")

        val operation = detectOperation(segments, hasConvoId, hasItems, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        if (hasItems && request.method == "GET") {
            request.url.parameters.queryParameter("limit")?.toLongOrNull()?.let {
                span.setAttribute("tracy.request.limit", it)
            }
            request.url.parameters.queryParameter("order")?.let {
                span.setAttribute("tracy.request.order", it)
            }
            request.url.parameters.queryParameter("after")?.let {
                span.setAttribute("tracy.request.after", it)
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonResponseAttributes(span, response)
        span.setAttribute("openai.api.type", "conversations")

        val segments = response.url.pathSegments.filter { it.isNotEmpty() }
        val convoIdx = segments.indexOf("conversations")
        val hasConvoId = convoIdx >= 0 && segments.size > convoIdx + 1
        val hasItems = hasConvoId && segments.contains("items")

        val operation = detectOperation(segments, hasConvoId, hasItems, response.requestMethod)
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        when {
            operation == "conversations.delete" -> {
                body["id"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
                body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.conversation.deleted", it) }
            }
            operation == "conversations.items.delete" -> {
                // Response is the conversation object after item deletion
                body["id"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
                body["created_at"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.conversation.created_at", it.toLong()) }
                // capture the deleted item id if present
                body["item_id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.item.id", it) }
            }
            operation == "conversations.items.create" -> {
                // Response is a list object of items
                body["conversation_id"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
                body["data"]?.let { data ->
                    if (data is JsonArray) {
                        span.setAttribute("tracy.conversation.items.count", data.size.toLong())
                    }
                }
                body["first_id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.items.first_id", it) }
                body["last_id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.items.last_id", it) }
            }
            operation == "conversations.items.list" -> {
                body["conversation_id"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
                body["data"]?.let { data ->
                    if (data is JsonArray) {
                        span.setAttribute("tracy.conversation.items.count", data.size.toLong())
                    }
                }
                body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.conversation.items.has_more", it) }
                body["first_id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.items.first_id", it) }
                body["last_id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.items.last_id", it) }
            }
            operation == "conversations.items.retrieve" -> {
                // Response is a single item
                body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.item.id", it) }
                body["type"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.item.type", it) }
                body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.item.status", it) }
                // conversation_id may be present
                body["conversation_id"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
            }
            else -> {
                // create, retrieve, update — conversation object
                body["id"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
                body["created_at"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.conversation.created_at", it.toLong()) }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private fun detectOperation(
        segments: List<String>,
        hasConvoId: Boolean,
        hasItems: Boolean,
        method: String,
    ): String {
        val itemsIdx = segments.indexOf("items")
        val hasItemId = hasItems && segments.size > itemsIdx + 1

        return when {
            hasItems && hasItemId && method == "DELETE" -> "conversations.items.delete"
            hasItems && hasItemId -> "conversations.items.retrieve"
            hasItems && method == "POST" -> "conversations.items.create"
            hasItems -> "conversations.items.list"
            hasConvoId && method == "DELETE" -> "conversations.delete"
            hasConvoId && method == "POST" -> "conversations.update"
            hasConvoId -> "conversations.retrieve"
            else -> "conversations.create"
        }
    }
}
