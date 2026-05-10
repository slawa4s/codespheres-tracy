/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Models API (list, retrieve, delete).
 *
 * See [Models API](https://platform.openai.com/docs/api-reference/models)
 */
internal class ModelsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operationName = resolveModelsOperation(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)

        // Extract model from URL path for retrieve/delete: /v1/models/{model_id}
        if (operationName == "models.retrieve" || operationName == "models.delete") {
            val modelId = request.url.pathSegments.lastOrNull()
            modelId?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val objectType = body["object"]?.jsonPrimitive?.contentOrNull

        when (objectType) {
            "model" -> {
                // Retrieve single model response
                body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.model.id", it) }
                body["object"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.object", it) }
                body["created"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.created", it) }
                body["owned_by"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.owned_by", it) }
            }
            "list" -> {
                span.setAttribute("tracy.response.object", "list")
            }
            else -> {
                // Delete response: { id, deleted, object }
                body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.deleted", it) }
                body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.model.id", it) }
            }
        }

        // Handle error response attributes
        if (response.isError()) {
            body["error"]?.jsonObject?.let { error ->
                error["message"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.error.message", it) }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private fun resolveModelsOperation(url: TracyHttpUrl, method: String): String {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val modelsIndex = segments.indexOf("models")
        val hasModelId = modelsIndex >= 0 && segments.size > modelsIndex + 1 && segments[modelsIndex + 1].isNotEmpty()
        return when {
            method == "DELETE" -> "models.delete"
            method == "GET" && hasModelId -> "models.retrieve"
            else -> "models.list"
        }
    }
}
