/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.moderations

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.openai.adapters.handlers.asString

/**
 * Handler for OpenAI Moderations API.
 *
 * See [Moderations API Reference](https://platform.openai.com/docs/api-reference/moderations)
 */
internal class ModerationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        body["input"]?.let { span.setAttribute("gen_ai.request.input", it.asString.orRedactedInput()) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val results = body["results"]
        if (results is JsonArray) {
            val anyFlagged = results.any { result ->
                result.jsonObject["flagged"]?.jsonPrimitive?.boolean == true
            }
            span.setAttribute("gen_ai.moderation.flagged", anyFlagged.toString())
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Moderations API does not use SSE streaming
    }
}
