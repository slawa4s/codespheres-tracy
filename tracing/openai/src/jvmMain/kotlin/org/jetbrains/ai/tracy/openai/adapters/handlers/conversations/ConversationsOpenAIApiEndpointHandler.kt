/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Conversations API (Realtime REST).
 *
 * Handles responses from conversations and conversation-item endpoints:
 * - `GET/DELETE /v1/realtime/conversations/{id}` — single conversation object
 * - `GET /v1/realtime/conversations/{id}/items` — list of conversation items
 * - `POST /v1/realtime/conversations/{id}/items` — created conversation item
 *
 * Extracted attributes:
 * - `gen_ai.conversation.id` — conversation or item `id`
 * - `tracy.conversation.created_at` — Unix timestamp of creation
 * - `tracy.conversation.deleted` — whether the resource was deleted
 *
 * For list responses (`object` contains "list"):
 * - `tracy.conversation.items.first_id`, `tracy.conversation.items.last_id`
 * - `tracy.conversation.items.has_more`, `tracy.conversation.items.count`
 *
 * For single-item responses:
 * - `tracy.conversation.item.id`, `tracy.conversation.item.type`, `tracy.conversation.item.status`
 *
 * See [OpenAI Realtime REST API Reference](https://platform.openai.com/docs/api-reference/realtime)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        // No request attributes are traced for conversations endpoints
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // Conversation-level fields present in all response types
        body["id"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.conversation.created_at", it)
        }
        body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.conversation.deleted", it)
        }

        val objectType = body["object"]?.jsonPrimitive?.contentOrNull
        if (objectType != null && objectType.contains("list")) {
            // List / items-create response
            body["first_id"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("tracy.conversation.items.first_id", it)
            }
            body["last_id"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("tracy.conversation.items.last_id", it)
            }
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("tracy.conversation.items.has_more", it)
            }
            val data = body["data"]
            val count = if (data is JsonArray) data.size.toLong() else 0L
            span.setAttribute("tracy.conversation.items.count", count)
        } else {
            // Single-item response
            body["id"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("tracy.conversation.item.id", it)
            }
            body["type"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("tracy.conversation.item.type", it)
            }
            body["status"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("tracy.conversation.item.status", it)
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
