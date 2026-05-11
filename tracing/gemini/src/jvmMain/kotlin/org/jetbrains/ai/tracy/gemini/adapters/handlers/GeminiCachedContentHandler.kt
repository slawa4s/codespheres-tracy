/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import kotlinx.serialization.json.*

/**
 * Handles Gemini Cached Content API requests and responses.
 *
 * Maps cache lifecycle operations (create, get, update, delete, list) to span attributes
 * under the `gen_ai.response.cache.*` and `gen_ai.request.cache.*` namespaces.
 *
 * See [Caching API Docs](https://ai.google.dev/gemini-api/docs/caching)
 */
class GeminiCachedContentHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val lastSegment = request.url.pathSegments.lastOrNull() ?: return
        val operation = resolveOperation(request.method, lastSegment)
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        if (operation == "caches.create") {
            span.setAttribute(GEN_AI_OUTPUT_TYPE, "cached_content")
            val body = request.body.asJson()?.jsonObject ?: return
            body["displayName"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.request.cache.display_name", it)
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val lastSegment = response.url.pathSegments.lastOrNull() ?: return

        // GET /cachedContents is the list endpoint; all other operations on /cachedContents
        // (including POST create) return a single CachedContent object.
        val isList = lastSegment == "cachedContents" && response.requestMethod == "GET"

        if (isList) {
            // List response: { "cachedContents": [...], "nextPageToken": "..." }
            val items = body["cachedContents"]?.let { if (it is JsonArray) it else null }
            span.setAttribute("gen_ai.response.list.count", (items?.size ?: 0).toLong())
            span.setAttribute("gen_ai.response.list.has_more", body.containsKey("nextPageToken"))
        } else {
            // Single cache response (create / get / update): { "name": "...", "model": "...", ... }
            body["name"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.response.cache.name", it)
            }
            body["model"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.response.cache.model", it)
            }
            body["createTime"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.response.cache.create_time", it)
            }
            body["expireTime"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.response.cache.expire_time", it)
            }
            body["usageMetadata"]?.jsonObject?.let { usage ->
                usage["totalTokenCount"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.response.cache.usage_metadata.total_token_count", it.toLong())
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private fun resolveOperation(method: String, lastSegment: String): String = when {
        lastSegment == "cachedContents" && method == "POST" -> "caches.create"
        lastSegment == "cachedContents" -> "caches.list"
        method == "PATCH" -> "caches.update"
        method == "DELETE" -> "caches.delete"
        else -> "caches.get"
    }
}
