/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.batches.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Populates the `MessageBatch` object attributes that are common to CREATE, RETRIEVE,
 * CANCEL, and DELETE responses.
 *
 * Sets the top-level OTel `gen_ai.output.type` (from `type`) and `gen_ai.response.id`
 * (from `id`) in addition to the namespaced `gen_ai.response.batch.{field}` attributes.
 *
 * See [MessageBatch](https://platform.claude.com/docs/en/api/messages#message_batch)
 */
internal fun Span.traceMessageBatch(body: JsonObject) {
    val span = this

    body["type"]?.jsonPrimitive?.content?.let {
        span.setAttribute(GEN_AI_OUTPUT_TYPE, it)
    }

    body["id"]?.jsonPrimitive?.content?.let { id ->
        span.setAttribute(GEN_AI_RESPONSE_ID, id)
    }

    traceMessageBatchFields(body, prefix = "gen_ai.response.batch")
}

/**
 * Populates `gen_ai.response.batches.{i}.{field}` attributes for each `MessageBatch`
 * element in the LIST response's `data` array.
 *
 * Does not touch the top-level OTel attrs — there is no single batch to attribute them to.
 *
 * See [MessageBatch](https://platform.claude.com/docs/en/api/messages#message_batch)
 */
internal fun Span.traceMessageBatches(batches: JsonArray) {
    for ((index, element) in batches.withIndex()) {
        val batch = element as? JsonObject ?: continue
        traceMessageBatchFields(batch, prefix = "gen_ai.response.batches.$index")
    }
}

/**
 * Writes every documented `MessageBatch` field under `{prefix}.{field}`.
 */
private fun Span.traceMessageBatchFields(body: JsonObject, prefix: String) {
    val span = this

    body["id"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.id", it)
    }

    body["type"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.type", it)
    }

    body["archived_at"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.archived_at", it)
    }

    body["cancel_initiated_at"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.cancel_initiated_at", it)
    }

    body["created_at"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.created_at", it)
    }

    body["ended_at"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.ended_at", it)
    }

    body["expires_at"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.expires_at", it)
    }

    body["processing_status"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.processing_status", it)
    }

    body["results_url"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.results_url", it)
    }

    body["request_counts"]?.jsonObject?.let { counts ->
        counts["processing"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.request_counts.processing", it)
        }
        counts["succeeded"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.request_counts.succeeded", it)
        }
        counts["errored"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.request_counts.errored", it)
        }
        counts["canceled"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.request_counts.canceled", it)
        }
        counts["expired"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("$prefix.request_counts.expired", it)
        }
    }
}
