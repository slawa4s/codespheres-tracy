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
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Models API.
 *
 * Handles list, retrieve, and delete operations on `/v1/models`.
 *
 * See: [Models API](https://platform.openai.com/docs/api-reference/models)
 */
internal class ModelsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "models")
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        val modelsIdx = segments.indexOf("models")
        val hasModelId = modelsIdx >= 0 && segments.size > modelsIdx + 1

        val operation = when {
            hasModelId && request.method == "DELETE" -> "models.delete"
            hasModelId -> "models.retrieve"
            else -> "models.list"
        }
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        if (hasModelId) {
            span.setAttribute(GEN_AI_REQUEST_MODEL, segments[modelsIdx + 1])
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonResponseAttributes(span, response)
        span.setAttribute("openai.api.type", "models")

        val segments = response.url.pathSegments.filter { it.isNotEmpty() }
        val modelsIdx = segments.indexOf("models")
        val hasModelId = modelsIdx >= 0 && segments.size > modelsIdx + 1

        val operation = when {
            hasModelId && response.requestMethod == "DELETE" -> "models.delete"
            hasModelId -> "models.retrieve"
            else -> "models.list"
        }
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        when (operation) {
            "models.retrieve" -> {
                body["id"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.response.model.id", it)
                }
                body["created"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.response.created", it.toLong()) }
                body["owned_by"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.owned_by", it) }
            }
            "models.delete" -> {
                body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.deleted", it) }
            }
        }
        // tracy.response.object flows from populateUnmappedAttributes (object field)
        body["object"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.object", it) }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
