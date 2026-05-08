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
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import kotlinx.serialization.json.*

/**
 * Parses Gemini Cached Contents API requests and responses.
 *
 * Handles create, get, update, delete, and list operations on `/v1beta/cachedContents`.
 *
 * See [Cached Contents API Docs](https://ai.google.dev/gemini-api/docs/caching)
 */
class GeminiCachedContentsHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["displayName"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.request.cache.display_name", it)
        }
        body["model"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.request.cache.model", it)
        }
        body["ttl"]?.let {
            span.setAttribute("gen_ai.request.cache.ttl", it.toString())
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // list response: { "cachedContents": [...], "nextPageToken": "..." }
        val cachedContents = body["cachedContents"]
        if (cachedContents is JsonArray) {
            span.setAttribute("gen_ai.response.list.count", cachedContents.size.toLong())
            val hasMore = body["nextPageToken"]?.jsonPrimitive?.contentOrNull?.isNotEmpty() == true
            span.setAttribute("gen_ai.response.list.has_more", hasMore)
            return
        }

        // create/get/update response: cached content object
        body["name"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.cache.name", it)
        }
        body["model"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.cache.model", it)
        }
        body["displayName"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.cache.display_name", it)
        }
        body["createTime"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.cache.create_time", it)
        }
        body["expireTime"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.cache.expire_time", it)
        }
        body["updateTime"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.cache.update_time", it)
        }
        body["usageMetadata"]?.jsonObject?.let { meta ->
            meta["totalTokenCount"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.response.cache.usage_metadata.total_token_count", it.toLong())
            }
        }

        // set output type for create/get responses
        if (body["name"] != null) {
            span.setAttribute(GEN_AI_OUTPUT_TYPE, "cached_content")
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
