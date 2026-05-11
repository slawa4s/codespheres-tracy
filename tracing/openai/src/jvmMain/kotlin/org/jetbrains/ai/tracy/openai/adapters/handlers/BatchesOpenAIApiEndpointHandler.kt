/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.populateUnmappedAttributes
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Handler for OpenAI Batches API.
 * See: https://platform.openai.com/docs/api-reference/batch
 */
internal class BatchesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val segments = request.url.pathSegments
        val method = request.method.uppercase()
        val batchesIdx = segments.indexOf("batches")
        val hasId = batchesIdx >= 0 && segments.size > batchesIdx + 1 && segments[batchesIdx + 1].isNotBlank()

        when {
            method == "POST" && !hasId -> handleCreateRequest(span, request)
            method == "GET" && !hasId -> handleListRequest(span, request)
            else -> Unit
        }
    }

    private fun handleCreateRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["input_file_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.batch.input_file.id", it)
        }
        body["endpoint"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.batch.endpoint", it)
        }
        body["completion_window"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.request.batch.completion_window", it)
        }
        body["output_expires_after"]?.jsonObject?.let { expires ->
            expires["anchor"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.request.batch.output_expires_after.anchor", it)
            }
            expires["seconds"]?.jsonPrimitive?.longOrNull?.let {
                span.setAttribute("tracy.request.batch.output_expires_after.seconds", it)
            }
        }
        body["metadata"]?.jsonObject?.let { metadata ->
            val keys = metadata.keys.joinToString(",")
            if (keys.isNotEmpty()) {
                span.setAttribute("tracy.request.metadata.keys", keys)
            }
        }

        span.populateUnmappedAttributes(body, batchCreateMappedRequestAttributes, PayloadType.REQUEST)
    }

    private fun handleListRequest(span: Span, request: TracyHttpRequest) {
        request.url.parameters.queryParameter("after")?.let {
            span.setAttribute("tracy.request.after", it)
        }
        request.url.parameters.queryParameter("limit")?.toLongOrNull()?.let {
            span.setAttribute("tracy.request.limit", it)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val segments = response.url.pathSegments
        val method = response.requestMethod.uppercase()
        val batchesIdx = segments.indexOf("batches")
        val hasId = batchesIdx >= 0 && segments.size > batchesIdx + 1 && segments[batchesIdx + 1].isNotBlank()

        when {
            method == "GET" && !hasId -> handleListResponse(span, body)
            else -> handleBatchObjectResponse(span, body)
        }
    }

    private fun handleListResponse(span: Span, body: JsonObject) {
        (body["data"] as? JsonArray)?.let { data ->
            span.setAttribute("tracy.response.list.count", data.size.toLong())
        }
        span.populateUnmappedAttributes(body, listOf("data", "object", "first_id", "last_id", "has_more"), PayloadType.RESPONSE)
    }

    private fun handleBatchObjectResponse(span: Span, body: JsonObject) {
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.batch.id", it) }
        body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.batch.status", it) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.batch.created_at", it) }

        body["request_counts"]?.jsonObject?.let { counts ->
            counts["total"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.batch.request_counts.total", it) }
            counts["completed"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.batch.request_counts.completed", it) }
            counts["failed"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.batch.request_counts.failed", it) }
        }

        span.populateUnmappedAttributes(body, batchResponseMappedAttributes, PayloadType.RESPONSE)
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private val batchCreateMappedRequestAttributes = listOf(
        "input_file_id", "endpoint", "completion_window", "output_expires_after", "metadata"
    )
    private val batchResponseMappedAttributes = listOf(
        "id", "status", "created_at", "request_counts", "object"
    )
}
