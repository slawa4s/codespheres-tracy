/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonArray
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
 * Populates the documented OpenAI `Batch` fields for CREATE / RETRIEVE / CANCEL
 * responses under `tracy.response.{field}`. Also writes the top-level OTel
 * `gen_ai.response.id` from `body.id`.
 */
internal fun Span.traceBatch(body: JsonObject) {
    body["id"]?.jsonPrimitive?.content?.let {
        setAttribute(GEN_AI_RESPONSE_ID, it)
    }
    traceBatchFields(body, prefix = "tracy.response")
}

/**
 * Populates `tracy.response.data.{i}.{field}` attributes for each `Batch`
 * element in the LIST response's `data` array.
 *
 * Does not touch the top-level OTel attrs — there is no single batch to attribute them to.
 */
internal fun Span.traceBatches(items: JsonArray) {
    for ((index, element) in items.withIndex()) {
        val item = element as? JsonObject ?: continue
        traceBatchFields(item, prefix = "tracy.response.data.$index")
    }
}

/**
 * Writes every documented `Batch` field under `{prefix}.{field}`, in the order
 * documented by the OpenAI API.
 */
private fun Span.traceBatchFields(body: JsonObject, prefix: String) {
    val span = this

    body["id"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.id", it)
    }
    body["completion_window"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.completion_window", it)
    }
    body["created_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.created_at", it)
    }
    body["endpoint"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.endpoint", it)
    }
    body["input_file_id"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.input_file_id", it)
    }
    body["object"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.object", it)
    }
    body["status"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.status", it)
    }
    body["cancelled_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.cancelled_at", it)
    }
    body["cancelling_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.cancelling_at", it)
    }
    body["completed_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.completed_at", it)
    }
    body["error_file_id"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.error_file_id", it)
    }
    body["errors"]?.let {
        span.setAttribute("$prefix.errors", it.toString())
    }
    body["expired_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.expired_at", it)
    }
    body["expires_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.expires_at", it)
    }
    body["failed_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.failed_at", it)
    }
    body["finalizing_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.finalizing_at", it)
    }
    body["in_progress_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.in_progress_at", it)
    }
    body["metadata"]?.let {
        span.setAttribute("$prefix.metadata", it.toString())
    }
    body["model"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.model", it)
    }
    body["output_file_id"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.output_file_id", it)
    }
    body["request_counts"]?.jsonObject?.let { counts ->
        counts["completed"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.request_counts.completed", it)
        }
        counts["failed"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.request_counts.failed", it)
        }
        counts["total"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.request_counts.total", it)
        }
    }
    body["usage"]?.jsonObject?.let { usage ->
        usage["input_tokens"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.usage.input_tokens", it)
        }
        usage["input_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.usage.input_tokens_details.cached_tokens", it)
        }
        usage["output_tokens"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.usage.output_tokens", it)
        }
        usage["output_tokens_details"]?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.usage.output_tokens_details.reasoning_tokens", it)
        }
        usage["total_tokens"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.usage.total_tokens", it)
        }
    }
}
