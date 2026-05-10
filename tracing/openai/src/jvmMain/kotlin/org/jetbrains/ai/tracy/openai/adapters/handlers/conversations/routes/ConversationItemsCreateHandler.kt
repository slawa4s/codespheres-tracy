/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `POST /conversations/{conversation_id}/items` endpoint.
 *
 * Sets [GEN_AI_OPERATION_NAME] to `conversations.items.create`, captures the
 * conversation ID from the URL path, and reads list-response pagination attributes
 * (`tracy.conversation.items.count`, `tracy.conversation.items.first_id`,
 * `tracy.conversation.items.last_id`) from the response body.
 */
internal class ConversationItemsCreateHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.items.create")
        extractConversationIdFromPath(request.url)
            ?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.items.create")
        val body = response.body.asJson()?.jsonObject ?: return
        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("tracy.conversation.items.count", data.size.toLong())
        }
        body["first_id"]?.let { span.setAttribute("tracy.conversation.items.first_id", it.jsonPrimitive.content) }
        body["last_id"]?.let { span.setAttribute("tracy.conversation.items.last_id", it.jsonPrimitive.content) }
    }
}
