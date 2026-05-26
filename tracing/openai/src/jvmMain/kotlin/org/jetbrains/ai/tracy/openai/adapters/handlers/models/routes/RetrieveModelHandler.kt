/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.models.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `GET /models/{model}` endpoint.
 *
 * `gen_ai.operation.name` is set by the parent [org.jetbrains.ai.tracy.openai.adapters.handlers.models.ModelsOpenAIApiEndpointHandler]
 * in both request and response phases.
 *
 * See [retrieve](https://developers.openai.com/api/reference/resources/models/methods/retrieve)
 */
internal class RetrieveModelHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractModelIdFromPath(request.url)?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it)
            span.setAttribute("tracy.request.model", it)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        extractModelIdFromPath(response.url)?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it) }
        span.traceModel(body)
    }
}
