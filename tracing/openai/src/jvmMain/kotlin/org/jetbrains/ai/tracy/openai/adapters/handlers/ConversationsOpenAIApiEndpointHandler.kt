/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Conversations API (`/v1/conversations`).
 *
 * Handles conversation management endpoints, including generating completions
 * within an existing conversation thread (`POST /v1/conversations/{id}/completions`).
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        val body = request.body.asJson()?.jsonObject ?: return

        body["messages"]?.let { messages ->
            for ((index, message) in messages.jsonArray.withIndex()) {
                val role = message.jsonObject["role"]?.jsonPrimitive?.content
                val content = message.jsonObject["content"]?.jsonPrimitive?.content

                span.setAttribute("gen_ai.prompt.$index.role", role)
                span.setAttribute("gen_ai.prompt.$index.content", content?.orRedactedInput())
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["output"]?.let { output ->
            for ((index, item) in output.jsonArray.withIndex()) {
                val role = item.jsonObject["role"]?.jsonPrimitive?.content
                val content = item.jsonObject["content"]?.jsonPrimitive?.content

                span.setAttribute("gen_ai.completion.$index.role", role)
                span.setAttribute("gen_ai.completion.$index.content", content?.orRedactedOutput())
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Streaming handling for Conversations API is not yet implemented
    }
}
