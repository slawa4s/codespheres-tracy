/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.batches.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Populates the shared Anthropic batch object attributes that are common to
 * CREATE, RETRIEVE, CANCEL, and DELETE responses.
 */
internal fun Span.traceAnthropicBatch(body: JsonObject) {
    val span = this
    span.setAttribute(GEN_AI_OUTPUT_TYPE, "message_batch")

    body["id"]?.jsonPrimitive?.content?.let { id ->
        span.setAttribute(GEN_AI_RESPONSE_ID, id)
        span.setAttribute("gen_ai.response.batch.id", id)
    }

    body["processing_status"]?.jsonPrimitive?.content?.let {
        span.setAttribute("gen_ai.response.batch.processing_status", it)
    }

    body["created_at"]?.jsonPrimitive?.content?.let {
        span.setAttribute("gen_ai.response.batch.created_at", it)
    }

    body["expires_at"]?.jsonPrimitive?.content?.let {
        span.setAttribute("gen_ai.response.batch.expires_at", it)
    }

    body["request_counts"]?.jsonObject?.let { counts ->
        counts["processing"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.batch.request_counts.processing", it)
        }
        counts["succeeded"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.batch.request_counts.succeeded", it)
        }
        counts["errored"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.batch.request_counts.errored", it)
        }
        counts["canceled"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.batch.request_counts.canceled", it)
        }
        counts["expired"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.batch.request_counts.expired", it)
        }
    }
}
