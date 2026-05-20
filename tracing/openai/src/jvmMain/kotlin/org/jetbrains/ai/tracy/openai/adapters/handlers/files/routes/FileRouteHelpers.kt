/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
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
 * Populates the shared OpenAI file object attributes common to CREATE and RETRIEVE responses.
 */
internal fun Span.traceOpenAIFileObject(body: JsonObject) {
    val span = this
    body["id"]?.let {
        val id = it.jsonPrimitive.content
        span.setAttribute("tracy.file.id", id)
        span.setAttribute(GEN_AI_RESPONSE_ID, id)
    }
    body["filename"]?.let { span.setAttribute("tracy.file.filename", it.jsonPrimitive.content) }
    body["purpose"]?.let { span.setAttribute("tracy.file.purpose", it.jsonPrimitive.content) }
    body["bytes"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.file.bytes", it) }
    body["created_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("tracy.file.created_at", it)
    }
    body["status"]?.let { span.setAttribute("tracy.file.status", it.jsonPrimitive.content) }
}
