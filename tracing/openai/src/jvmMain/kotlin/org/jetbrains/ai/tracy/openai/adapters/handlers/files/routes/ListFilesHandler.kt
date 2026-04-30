/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles `GET /files` — list files.
 *
 * See [List files](https://platform.openai.com/docs/api-reference/files/list)
 */
internal class ListFilesHandler : FileRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val params = request.url.parameters
        params.queryParameter("purpose")?.let { span.setAttribute("tracy.request.purpose", it) }
        params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
        params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["first_id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.files.first_id", it) }
        body["last_id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.files.last_id", it) }
        body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.files.has_more", it) }
        val data = body["data"]
        val count = if (data is JsonArray) data.size.toLong() else 0L
        span.setAttribute("tracy.response.files.count", count)
    }
}
