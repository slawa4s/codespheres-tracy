/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `GET /v1/files` endpoint.
 */
internal class ListFilesHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // No request-side attributes for list.
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("gen_ai.response.list.count", data.size.toLong())
        }
        body["has_more"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.list.has_more", it)
        }
        body["first_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.list.first_id", it)
        }
        body["last_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.list.last_id", it)
        }
    }
}
