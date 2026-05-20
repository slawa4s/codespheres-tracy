/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.models.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `GET /v1/models/{model_id}` endpoint.
 *
 * The URL path alias (e.g. `claude-haiku-4-5`) is used for both `gen_ai.request.model`
 * and `gen_ai.response.model` so the observed model attribute matches what the caller
 * supplied. The versioned id from the response body (e.g. `claude-haiku-4-5-20251001`)
 * is exposed via `gen_ai.response.id` and `gen_ai.response.model.id`.
 */
internal class RetrieveModelHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractModelAliasFromPath(request.url)?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // Pin GEN_AI_RESPONSE_MODEL to the URL alias so it lines up with
        // GEN_AI_REQUEST_MODEL — body.id is the versioned id.
        extractModelAliasFromPath(response.url)?.let {
            span.setAttribute(GEN_AI_RESPONSE_MODEL, it)
        }

        span.traceBetaModelInfo(body)
    }
}
