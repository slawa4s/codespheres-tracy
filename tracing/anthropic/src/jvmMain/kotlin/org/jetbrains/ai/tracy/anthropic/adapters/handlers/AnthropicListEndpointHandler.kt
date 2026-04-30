/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic list endpoints (batches, files, models).
 *
 * Sets stable semconv network attributes on requests and parses the standard Anthropic page
 * envelope on responses (data count, has_more, first_id, last_id). Sets `gen_ai.operation.name`
 * and `anthropic.api.type` for all recognised batch lifecycle operations:
 * - GET  `…/{type}`       → `{type}.list`
 * - GET  `…/{type}/{id}`  → `{type}.retrieve`
 * - POST `…/{type}`       → `{type}.create`
 * - POST `…/cancel`       → `batches.cancel`
 *
 * See:
 * - [Messages Batches API](https://docs.anthropic.com/en/api/messages-batches)
 * - [Files API](https://docs.anthropic.com/en/api/files)
 * - [Models API](https://docs.anthropic.com/en/api/models)
 */
internal class AnthropicListEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gen_ai.provider.name", "anthropic")
        span.setAttribute("server.address", request.url.host)
        span.setAttribute("server.port", if (request.url.scheme == "https") 443L else 80L)

        val detectedType = detectApiType(request.url.pathSegments)
        span.setAttribute("anthropic.api.type", detectedType)

        val lastSegment = request.url.pathSegments.lastOrNull()
        val operationName = when (request.method) {
            "POST" -> when {
                lastSegment == "cancel" -> "batches.cancel"
                lastSegment == detectedType -> "$detectedType.create"
                else -> null
            }
            "GET" -> when {
                lastSegment == detectedType -> "$detectedType.list"
                else -> "$detectedType.retrieve"
            }
            else -> null
        }
        if (operationName != null) {
            span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute("http.response.status_code", response.code.toLong())

        val body = response.body.asJson()?.jsonObject ?: return

        body["data"]?.jsonArray?.size?.toLong()?.let {
            span.setAttribute("gen_ai.response.list.count", it)
        }
        body["has_more"]?.jsonPrimitive?.boolean?.let {
            span.setAttribute("gen_ai.response.list.has_more", it)
        }
        body["first_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.list.first_id", it)
        }
        body["last_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.list.last_id", it)
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private fun detectApiType(pathSegments: List<String>): String = when {
        "batches" in pathSegments -> "batches"
        "files" in pathSegments -> "files"
        "models" in pathSegments -> "models"
        else -> "messages"
    }
}
