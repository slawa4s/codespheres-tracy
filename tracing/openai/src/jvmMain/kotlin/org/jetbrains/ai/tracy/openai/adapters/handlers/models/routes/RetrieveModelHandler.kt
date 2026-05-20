/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.models.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `GET /v1/models/{model_id}` endpoint.
 */
internal class RetrieveModelHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "models.retrieve")
        extractModelIdFromPath(request.url)?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["object"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.object", it)
        }
        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.model.id", it)
        }
        extractModelIdFromPath(response.url)?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it) }
    }
}
