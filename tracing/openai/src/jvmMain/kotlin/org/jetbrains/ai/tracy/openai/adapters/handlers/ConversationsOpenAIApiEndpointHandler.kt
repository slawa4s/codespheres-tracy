/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Conversations API.
 *
 * See [Conversations API](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operationName = resolveConversationsOperation(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
        span.setAttribute("openai.api.type", "conversations")

        // Extract conversation_id from URL path for items operations
        extractConversationIdFromPath(request.url)?.let { span.setAttribute("gen_ai.conversation.id", it) }
        // Extract item_id from URL path for item-specific operations
        extractItemIdFromPath(request.url)?.let { span.setAttribute("tracy.conversation.item.id", it) }

        if (request.method == "GET") {
            val params = request.url.parameters
            params.queryParameter("limit")?.toLongOrNull()?.let { span.setAttribute("tracy.request.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
            params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.conversation.id", it) }
        body["created_at"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.conversation.created_at", it) }
        body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.conversation.deleted", it) }

        // Single item response (retrieve item)
        val objectType = body["object"]?.jsonPrimitive?.contentOrNull
        if (objectType == "realtime.item" || body.containsKey("type") && !body.containsKey("data")) {
            body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.conversation.item.id", it) }
            body["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.conversation.item.type", it) }
            body["status"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.conversation.item.status", it) }
        }

        // List response (conversations list or items list)
        val data = body["data"] as? JsonArray
        if (data != null) {
            span.setAttribute("tracy.response.list.count", data.size.toLong())
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.has_more", it) }
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.conversation.items.has_more", it) }
            body["first_id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.conversation.items.first_id", it) }
            body["last_id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.conversation.items.last_id", it) }
            span.setAttribute("tracy.conversation.items.count", data.size.toLong())
        }

        // Item delete response
        body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
            if (it) {
                body["item_id"]?.jsonPrimitive?.contentOrNull?.let { itemId ->
                    span.setAttribute("tracy.conversation.item.id", itemId)
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val convsIndex = segments.indexOf("conversations")
        return if (convsIndex >= 0 && segments.size > convsIndex + 1) segments[convsIndex + 1] else null
    }

    private fun extractItemIdFromPath(url: TracyHttpUrl): String? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val itemsIndex = segments.indexOf("items")
        return if (itemsIndex >= 0 && segments.size > itemsIndex + 1) segments[itemsIndex + 1] else null
    }

    private fun resolveConversationsOperation(url: TracyHttpUrl, method: String): String {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val convsIndex = segments.indexOf("conversations")
        val hasConvId = convsIndex >= 0 && segments.size > convsIndex + 1
        val hasItems = segments.contains("items")
        val hasItemId = hasItems && segments.size > segments.indexOf("items") + 1

        return when {
            method == "POST" && !hasConvId -> "conversations.create"
            method == "GET" && !hasConvId -> "conversations.list"
            method == "GET" && hasConvId && !hasItems -> "conversations.retrieve"
            // The OpenAI SDK sends PATCH or POST for updates depending on SDK version
            method == "PATCH" && hasConvId && !hasItems -> "conversations.update"
            method == "POST" && hasConvId && !hasItems -> "conversations.update"
            method == "DELETE" && hasConvId && !hasItems -> "conversations.delete"
            method == "POST" && hasItems -> "conversations.items.create"
            method == "GET" && hasItems && hasItemId -> "conversations.items.retrieve"
            method == "GET" && hasItems && !hasItemId -> "conversations.items.list"
            method == "DELETE" && hasItems -> "conversations.items.delete"
            else -> "conversations.retrieve"
        }
    }
}
