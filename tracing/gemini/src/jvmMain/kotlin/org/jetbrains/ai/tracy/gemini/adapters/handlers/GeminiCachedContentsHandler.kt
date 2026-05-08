/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Parses Gemini Cached Contents API requests and responses.
 *
 * Handles CRUD operations on the `cachedContents` resource:
 * `GET /v1beta/cachedContents` (list), `GET /v1beta/cachedContents/{name}` (get),
 * `POST /v1beta/cachedContents` (create), `PATCH /v1beta/cachedContents/{name}` (update),
 * `DELETE /v1beta/cachedContents/{name}` (delete).
 *
 * ## Request attribute mapping
 *
 * | HTTP method | `gen_ai.operation.name` |
 * |-------------|------------------------|
 * | GET (collection) | `list`              |
 * | GET (resource)   | `get`               |
 * | POST        | `create`               |
 * | PATCH       | `update`               |
 * | DELETE      | `delete`               |
 *
 * ## Response attribute mapping (list operation)
 *
 * | Source field        | OTel attribute                   |
 * |---------------------|----------------------------------|
 * | `cachedContents[]`  | `gen_ai.response.list.count`     |
 * | `nextPageToken`     | `gen_ai.response.list.has_more`  |
 *
 * See: [Gemini Caching API](https://ai.google.dev/api/caching)
 */
internal class GeminiCachedContentsHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gemini.api.type", "cachedContents")

        val operationName = when (request.method.uppercase()) {
            "GET" -> {
                // Collection URL ends with "cachedContents" → list; resource URL has an ID after it → get
                if (request.url.pathSegments.lastOrNull() == "cachedContents") "list" else "get"
            }
            "POST" -> "create"
            "PATCH" -> "update"
            "DELETE" -> "delete"
            else -> null
        }
        operationName?.let { span.setAttribute(GEN_AI_OPERATION_NAME, it) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // See: https://ai.google.dev/api/caching#v1beta.cachedContents.list
        val body = response.body.asJson()?.jsonObject ?: return

        val cachedContents = body["cachedContents"]?.jsonArray ?: return
        span.setAttribute("gen_ai.response.list.count", cachedContents.size.toLong())

        val nextPageToken = body["nextPageToken"]?.jsonPrimitive?.content
        val hasMore = !nextPageToken.isNullOrEmpty()
        span.setAttribute("gen_ai.response.list.has_more", hasMore.toString())
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
