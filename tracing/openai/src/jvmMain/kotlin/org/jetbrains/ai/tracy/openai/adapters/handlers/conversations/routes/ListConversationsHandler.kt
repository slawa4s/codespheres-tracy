/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.ConversationsOpenAIApiEndpointHandler

/**
 * Handles [ConversationsOpenAIApiEndpointHandler.ConversationRoute.LIST] endpoint:
 * `GET /conversations`.
 */
internal class ListConversationsHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val params = request.url.parameters
        params.queryParameter("after")?.let { span.setAttribute("gen_ai.request.after", it) }
        params.queryParameter("limit")?.let { span.setAttribute("gen_ai.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("gen_ai.request.order", it) }
    }

    /**
     * Response: `{ object: "list", data: Conversation[], first_id, last_id, has_more }`.
     *
     * Sets gen_ai.operation.name to "conversations.list". The response body's object field
     * is "list", which is not a valid operation name.
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.list")
        val body = response.body.asJson()?.jsonObject ?: return

        body["first_id"]?.let { span.setAttribute("gen_ai.response.first_id", it.jsonPrimitive.content) }
        body["last_id"]?.let { span.setAttribute("gen_ai.response.last_id", it.jsonPrimitive.content) }
        body["has_more"]?.let { span.setAttribute("gen_ai.response.has_more", it.jsonPrimitive.boolean) }

        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("gen_ai.response.conversations_count", data.size.toLong())
        }
    }
}
