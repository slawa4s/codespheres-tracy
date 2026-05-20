/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `POST /conversations/{conversation_id}` endpoint.
 */
internal class UpdateConversationHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("tracy.request.conversation_id", it)
        }
        val body = request.body.asJson()?.jsonObject ?: return
        body["metadata"]?.let {
            span.setAttribute("tracy.request.metadata", it.toString())
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceConversation(body)
    }
}
