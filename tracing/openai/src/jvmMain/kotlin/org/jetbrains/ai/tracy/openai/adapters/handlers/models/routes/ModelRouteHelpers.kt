/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.models.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Extracts the model id from a path like `/v1/models/{model_id}` or `/models/{model_id}`.
 * Returns `null` for the collection path.
 */
internal fun extractModelIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val modelsIndex = segments.indexOf("models")
    if (modelsIndex == -1 || segments.size <= modelsIndex + 1) return null
    return segments[modelsIndex + 1].takeIf { it.isNotBlank() }
}

/**
 * Trace a single `Model` object (RETRIEVE response) under `tracy.response.{field}`.
 */
internal fun Span.traceModel(body: JsonObject) {
    traceModelFields(body, prefix = "tracy.response")
}

/**
 * Trace an array of `Model` objects under `tracy.response.data.{i}.{field}` (LIST response).
 */
internal fun Span.traceModels(items: JsonArray) {
    for ((index, element) in items.withIndex()) {
        val item = element as? JsonObject ?: continue
        traceModelFields(item, prefix = "tracy.response.data.$index")
    }
}

/**
 * Writes the documented `Model` fields under `{prefix}.{field}`.
 *
 * Model schema:
 * - `id` (string)
 * - `created` (long, unix epoch seconds)
 * - `object` (string, typically `"model"`)
 * - `owned_by` (string)
 */
private fun Span.traceModelFields(body: JsonObject, prefix: String) {
    val span = this
    body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("$prefix.id", it) }
    body["created"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("$prefix.created", it) }
    body["object"]?.jsonPrimitive?.content?.let { span.setAttribute("$prefix.object", it) }
    body["owned_by"]?.jsonPrimitive?.content?.let { span.setAttribute("$prefix.owned_by", it) }
}
