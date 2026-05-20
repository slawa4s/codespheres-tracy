/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.models.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `DELETE /models/{model}` endpoint (delete a fine-tuned model).
 *
 * Response: ModelDeleted { id, deleted, object }
 */
internal class DeleteModelHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "models.delete")
        extractModelIdFromPath(request.url)?.let {
            span.setAttribute("tracy.request.model", it)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.id", it)
        }
        body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.response.deleted", it)
        }
        body["object"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.object", it)
        }
    }
}
