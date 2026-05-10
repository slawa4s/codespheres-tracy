/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.populateUnmappedAttributes
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Handler for OpenAI Conversations API.
 * See: https://platform.openai.com/docs/api-reference/conversations
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val segments = request.url.pathSegments
        val method = request.method.uppercase()
        val convIdx = segments.indexOf("conversations")
        val hasConvId = convIdx >= 0 && segments.size > convIdx + 1 && segments[convIdx + 1].isNotBlank()
        val hasItems = segments.contains("items")

        // Extract conversation ID from URL for item operations
        if (hasConvId && hasItems) {
            span.setAttribute("gen_ai.conversation.id", segments[convIdx + 1])
        }

        // List request params
        if (hasItems && method == "GET") {
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
        val segments = response.url.pathSegments
        val method = response.requestMethod.uppercase()
        val convIdx = segments.indexOf("conversations")
        val hasConvId = convIdx >= 0 && segments.size > convIdx + 1 && segments[convIdx + 1].isNotBlank()
        val hasItems = segments.contains("items")
        val itemsIdx = segments.indexOf("items")
        val hasItemId = hasItems && itemsIdx >= 0 && segments.size > itemsIdx + 1 && segments[itemsIdx + 1].isNotBlank()

        when {
            hasItems && hasItemId && method == "GET" -> handleItemRetrieveResponse(span, body)
            hasItems && hasItemId && method == "DELETE" -> handleItemDeleteResponse(span, body, segments, convIdx)
            hasItems && method == "GET" -> handleItemsListResponse(span, body)
            hasItems && method == "POST" -> handleItemsCreateResponse(span, body)
            hasConvId && method == "DELETE" -> handleConversationDeleteResponse(span, body)
            else -> handleConversationResponse(span, body)
        }
    }

    private fun handleConversationResponse(span: Span, body: JsonObject) {
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.conversation.id", it) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.conversation.created_at", it) }
        span.populateUnmappedAttributes(body, listOf("id", "created_at", "object"), PayloadType.RESPONSE)
    }

    private fun handleConversationDeleteResponse(span: Span, body: JsonObject) {
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.conversation.id", it) }
        body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.conversation.deleted", it) }
        span.populateUnmappedAttributes(body, listOf("id", "deleted", "object"), PayloadType.RESPONSE)
    }

    private fun handleItemsCreateResponse(span: Span, body: JsonObject) {
        // Response may be either a list object or a single item
        val objectType = body["object"]?.jsonPrimitive?.content
        if (objectType == "list" || body.containsKey("data")) {
            extractListAttributes(span, body)
        } else {
            // Single item created - check if conversation has items info
            body["id"]?.jsonPrimitive?.content?.let {
                // this might be a list wrapper
                span.setAttribute("tracy.conversation.items.first_id", it)
                span.setAttribute("tracy.conversation.items.last_id", it)
                span.setAttribute("tracy.conversation.items.count", 1L)
            }
        }
        // conversation ID from URL context already set in request phase for item operations
        span.populateUnmappedAttributes(body, listOf("id", "object", "data", "first_id", "last_id", "has_more"), PayloadType.RESPONSE)
    }

    private fun handleItemsListResponse(span: Span, body: JsonObject) {
        extractListAttributes(span, body)
        span.populateUnmappedAttributes(body, listOf("object", "data", "first_id", "last_id", "has_more"), PayloadType.RESPONSE)
    }

    private fun extractListAttributes(span: Span, body: JsonObject) {
        (body["data"] as? JsonArray)?.let { data ->
            span.setAttribute("tracy.conversation.items.count", data.size.toLong())
        }
        body["first_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.conversation.items.first_id", it)
        }
        body["last_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.conversation.items.last_id", it)
        }
        body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.conversation.items.has_more", it)
        }
    }

    private fun handleItemRetrieveResponse(span: Span, body: JsonObject) {
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.item.id", it) }
        body["type"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.item.type", it) }
        body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.item.status", it) }
        span.populateUnmappedAttributes(body, listOf("id", "type", "status", "object"), PayloadType.RESPONSE)
    }

    private fun handleItemDeleteResponse(span: Span, body: JsonObject, segments: List<String>, convIdx: Int) {
        // Extract conversation ID from URL
        if (convIdx >= 0 && segments.size > convIdx + 1) {
            span.setAttribute("gen_ai.conversation.id", segments[convIdx + 1])
        }
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.item.id", it) }
        body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.conversation.deleted", it) }
        // created_at from deleted item/conversation
        body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.conversation.created_at", it) }
        span.populateUnmappedAttributes(body, listOf("id", "deleted", "created_at", "object"), PayloadType.RESPONSE)
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
