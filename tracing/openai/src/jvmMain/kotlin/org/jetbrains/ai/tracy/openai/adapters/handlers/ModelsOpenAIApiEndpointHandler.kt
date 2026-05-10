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
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Handler for OpenAI Models API.
 * See: https://platform.openai.com/docs/api-reference/models
 */
internal class ModelsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val segments = request.url.pathSegments
        val modelsIdx = segments.indexOf("models")
        val hasId = modelsIdx >= 0 && segments.size > modelsIdx + 1 && segments[modelsIdx + 1].isNotBlank()
        if (hasId) {
            span.setAttribute(GEN_AI_REQUEST_MODEL, segments[modelsIdx + 1])
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val segments = response.url.pathSegments
        val method = response.requestMethod.uppercase()
        val modelsIdx = segments.indexOf("models")
        val hasId = modelsIdx >= 0 && segments.size > modelsIdx + 1 && segments[modelsIdx + 1].isNotBlank()

        // Handle error responses for any method
        (body["error"] as? JsonObject)?.let { error ->
            error["message"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.error.message", it) }
            error["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.error.type", it) }
            error["code"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.error.code", it) }
        }

        when {
            method == "GET" && !hasId -> {
                // models.list - response has {"object": "list", "data": [...]}
                body["object"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.response.object", it)
                }
                (body["data"] as? JsonArray)?.let { data ->
                    span.setAttribute("tracy.response.list.count", data.size.toLong())
                }
                span.populateUnmappedAttributes(body, listOf("object", "data", "first_id", "last_id", "has_more"), PayloadType.RESPONSE)
            }
            method == "GET" && hasId -> {
                // models.retrieve - response is the model object
                body["object"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.response.object", it)
                }
                body["id"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.response.model.id", it)
                }
                body["created"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.response.created", it)
                }
                body["owned_by"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.response.owned_by", it)
                }
                span.populateUnmappedAttributes(body, listOf("object", "id", "created", "owned_by"), PayloadType.RESPONSE)
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
