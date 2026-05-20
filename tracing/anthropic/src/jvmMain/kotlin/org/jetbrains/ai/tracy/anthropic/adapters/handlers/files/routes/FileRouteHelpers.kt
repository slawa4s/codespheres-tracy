/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Populates `FileMetadata` attributes for CREATE and RETRIEVE responses under
 * `gen_ai.response.file.{field}`, plus the top-level OTel `gen_ai.response.id`.
 *
 * See (FileMetadata)[https://platform.claude.com/docs/en/api/beta/files#file_metadata]
 */
internal fun Span.traceFileMetadata(body: JsonObject) {
    body["id"]?.jsonPrimitive?.content?.let {
        setAttribute(GEN_AI_RESPONSE_ID, it)
    }
    traceFileMetadataFields(body, prefix = "gen_ai.response.file")
}

/**
 * Populates `gen_ai.response.files.{i}.{field}` attributes for each `FileMetadata`
 * element in the LIST response's `data` array.
 *
 * Does not touch the top-level OTel attrs — there is no single file to attribute them to.
 */
internal fun Span.traceFileMetadata(items: JsonArray) {
    for ((index, element) in items.withIndex()) {
        val item = element as? JsonObject ?: continue
        traceFileMetadataFields(item, prefix = "gen_ai.response.files.$index")
    }
}

/**
 * Writes every documented `FileMetadata` field under `{prefix}.{field}`.
 */
private fun Span.traceFileMetadataFields(body: JsonObject, prefix: String) {
    val span = this
    body["id"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.id", it)
    }
    body["type"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.type", it)
    }
    body["filename"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.filename", it)
    }
    body["mime_type"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.mime_type", it)
    }
    body["size_bytes"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.size_bytes", it)
    }
    body["downloadable"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.downloadable", it)
    }
    body["created_at"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.created_at", it)
    }
    body["scope"]?.jsonObject?.let { scope ->
        scope["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("$prefix.scope.id", it)
        }
        scope["type"]?.jsonPrimitive?.content?.let {
            span.setAttribute("$prefix.scope.type", it)
        }
    }
}
