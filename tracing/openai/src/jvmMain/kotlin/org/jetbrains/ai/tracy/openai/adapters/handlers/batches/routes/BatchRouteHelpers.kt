/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Extracts the batch id from a path like `/v1/batches/{batch_id}` or
 * `/v1/batches/{batch_id}/cancel`. Returns `null` for collection paths.
 */
internal fun extractBatchIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val batchesIndex = segments.indexOf("batches")
    if (batchesIndex == -1 || segments.size <= batchesIndex + 1) return null
    val id = segments[batchesIndex + 1]
    return id.takeIf { it.isNotBlank() }
}

/**
 * Populates the shared OpenAI batch object attributes that are common to
 * CREATE, RETRIEVE, and CANCEL responses.
 */
internal fun Span.traceOpenAIBatchObject(body: JsonObject) {
    val span = this
    body["id"]?.let {
        val id = it.jsonPrimitive.content
        span.setAttribute("tracy.batch.id", id)
        span.setAttribute(GEN_AI_RESPONSE_ID, id)
    }
    body["status"]?.let { span.setAttribute("tracy.batch.status", it.jsonPrimitive.content) }
    body["input_file_id"]?.let {
        span.setAttribute("tracy.batch.input_file_id", it.jsonPrimitive.content)
    }
    body["endpoint"]?.let { span.setAttribute("tracy.batch.endpoint", it.jsonPrimitive.content) }
    body["completion_window"]?.let {
        span.setAttribute("tracy.batch.completion_window", it.jsonPrimitive.content)
    }
    body["created_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("tracy.batch.created_at", it)
    }
    body["request_counts"]?.jsonObject?.let { counts ->
        counts["total"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.batch.request_counts.total", it)
        }
        counts["completed"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.batch.request_counts.completed", it)
        }
        counts["failed"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.batch.request_counts.failed", it)
        }
    }
}
