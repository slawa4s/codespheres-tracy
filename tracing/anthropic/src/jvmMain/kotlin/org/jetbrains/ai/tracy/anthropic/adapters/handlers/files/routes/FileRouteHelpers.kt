/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Populates the shared Anthropic file object attributes that are common to
 * CREATE and RETRIEVE responses.
 */
internal fun Span.traceAnthropicFileObject(body: JsonObject) {
    val span = this
    body["id"]?.jsonPrimitive?.content?.let { id ->
        span.setAttribute(GEN_AI_RESPONSE_ID, id)
        span.setAttribute("gen_ai.response.file.id", id)
    }
    body["filename"]?.jsonPrimitive?.content?.let {
        span.setAttribute("gen_ai.response.file.filename", it)
    }
    body["mime_type"]?.jsonPrimitive?.content?.let {
        span.setAttribute("gen_ai.response.file.mime_type", it)
    }
    body["size_bytes"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("gen_ai.response.file.size_bytes", it)
    }
    body["downloadable"]?.jsonPrimitive?.content?.let {
        span.setAttribute("gen_ai.response.file.downloadable", it)
    }
    body["created_at"]?.jsonPrimitive?.content?.let {
        span.setAttribute("gen_ai.response.file.created_at", it)
    }
}
