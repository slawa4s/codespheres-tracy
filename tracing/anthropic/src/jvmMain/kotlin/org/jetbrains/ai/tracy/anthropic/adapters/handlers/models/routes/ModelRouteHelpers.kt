/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.models.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Extracts the model alias from a path like `/v1/models/{model_alias}`.
 *
 * Returns `null` for the list collection path `/v1/models`.
 */
internal fun extractModelAliasFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val modelsIndex = segments.indexOf("models")
    if (modelsIndex == -1) return null
    val after = segments.drop(modelsIndex + 1).filter { it.isNotBlank() }
    return after.firstOrNull()
}

/**
 * Populates `BetaModelInfo` attributes for the RETRIEVE response under
 * `gen_ai.response.model.{field}`, plus the top-level OTel `gen_ai.response.id`.
 *
 * See [BetaModelInfo](https://platform.claude.com/docs/en/api/models#response)
 */
internal fun Span.traceBetaModelInfo(body: JsonObject) {
    body["id"]?.jsonPrimitive?.content?.let {
        setAttribute(GEN_AI_RESPONSE_ID, it)
    }
    traceBetaModelInfoFields(body, prefix = "gen_ai.response.model")
}

/**
 * Populates `gen_ai.response.models.{i}.{field}` attributes for each `BetaModelInfo`
 * element in the LIST response's `data` array.
 *
 * Does not touch the top-level OTel attrs — there is no single model to attribute them to.
 */
internal fun Span.traceBetaModelInfo(items: JsonArray) {
    for ((index, element) in items.withIndex()) {
        val item = element as? JsonObject ?: continue
        traceBetaModelInfoFields(item, prefix = "gen_ai.response.models.$index")
    }
}

/**
 * Writes every documented `BetaModelInfo` field under `{prefix}.{field}`.
 *
 * `capabilities` is emitted as a single serialized JSON object string rather than
 * being flattened per key, because the schema is open-ended and the structure
 * varies across capability types.
 */
private fun Span.traceBetaModelInfoFields(body: JsonObject, prefix: String) {
    val span = this
    body["id"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.id", it)
    }
    body["type"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.type", it)
    }
    body["display_name"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.display_name", it)
    }
    body["created_at"]?.jsonPrimitive?.content?.let {
        span.setAttribute("$prefix.created_at", it)
    }
    body["max_input_tokens"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.max_input_tokens", it)
    }
    body["max_tokens"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.max_tokens", it)
    }
    body["capabilities"]?.let {
        span.setAttribute("$prefix.capabilities", it.toString())
    }
}
