/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Extracts the file id from a path like `/v1/files/{file_id}` or
 * `/v1/files/{file_id}/content`. Returns `null` for collection paths.
 */
internal fun extractFileIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val filesIndex = segments.indexOf("files")
    if (filesIndex == -1 || segments.size <= filesIndex + 1) return null
    val id = segments[filesIndex + 1]
    return id.takeIf { it.isNotBlank() }
}

/**
 * Populates the documented OpenAI `FileObject` fields for CREATE / RETRIEVE responses
 * under `tracy.response.{field}`. Also writes the top-level OTel `gen_ai.response.id`
 * from `body.id`.
 */
internal fun Span.traceFileObject(body: JsonObject) {
    body["id"]?.jsonPrimitive?.content?.let {
        setAttribute(GEN_AI_RESPONSE_ID, it)
    }
    traceFileObjectFields(body, prefix = "tracy.response")
}

/**
 * Populates `tracy.response.data.{i}.{field}` attributes for each `FileObject`
 * element in the LIST response's `data` array.
 *
 * Does not touch the top-level OTel attrs — there is no single file to attribute them to.
 */
internal fun Span.traceFileObjects(items: JsonArray) {
    for ((index, element) in items.withIndex()) {
        val item = element as? JsonObject ?: continue
        traceFileObjectFields(item, prefix = "tracy.response.data.$index")
    }
}

/**
 * Writes every documented `FileObject` field under `{prefix}.{field}`, in the order
 * documented by the OpenAI API. The deprecated fields `status` and `status_details`
 * are still traced.
 */
private fun Span.traceFileObjectFields(body: JsonObject, prefix: String) {
    val span = this

    body["id"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.id", it)
    }
    body["bytes"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.bytes", it)
    }
    body["created_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.created_at", it)
    }
    body["filename"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.filename", it)
    }
    body["object"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.object", it)
    }
    body["purpose"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.purpose", it)
    }
    body["status"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.status", it)
    }
    body["expires_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.expires_at", it)
    }
    body["status_details"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.status_details", it)
    }
}
