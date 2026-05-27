/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.policy.orRedactedInput

/**
 * Shared tracing helper for the `CachedContent` resource used by every cached-contents route
 * handler. Centralizes the field-by-field attribute mapping so the per-route handlers don't
 * duplicate it.
 *
 * Three entry points:
 *   - [traceRequest]    — CREATE / PATCH request body.
 *   - [traceResponse]   — CREATE / GET / PATCH response body (single resource).
 *   - [traceListItem]   — LIST response item (shallow per-item metadata only).
 *
 * Attribute conventions:
 *   - `gen_ai.*`    — OTel semconv (request/response model, response id).
 *   - `tracy.*`     — Tracy-custom (everything else; not in the OTel GenAI registry).
 *
 * See: [Gemini Caching API](https://ai.google.dev/api/caching), [CachedContent resource](https://ai.google.dev/api/caching#CachedContent).
 */
internal object CachedContentTracer {

    fun traceRequest(span: Span, cachedContent: JsonObject) {
        cachedContent["model"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it)
        }
        cachedContent["displayName"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.request.display_name", it)
        }
        cachedContent["expireTime"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.request.expire_time", it)
        }
        cachedContent["ttl"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.request.ttl", it)
        }

        // contents[]: traced with the same single-text-vs-multi-part convention as
        // GeminiContentGenHandler.handleRequestAttributes — keep the two consistent so
        // backends see the same shape regardless of whether prompts arrive directly or via
        // a cached content reference.
        (cachedContent["contents"] as? JsonArray)?.let { contents ->
            for ((index, message) in contents.withIndex()) {
                val role = message.jsonObject["role"]?.jsonPrimitive?.contentOrNull
                span.setAttribute("gen_ai.prompt.$index.role", role)

                val parts = message.jsonObject["parts"]
                val text = parts?.singleTextMessageInParts()
                if (text != null) {
                    span.setAttribute("gen_ai.prompt.$index.content", text.orRedactedInput())
                } else {
                    span.setAttribute("gen_ai.prompt.$index.content", parts?.toString()?.orRedactedInput())
                }
            }
        }

        // tools[]: per-function fan-out, mirrors GeminiContentGenHandler conventions.
        (cachedContent["tools"] as? JsonArray)?.let { tools ->
            for ((index, tool) in tools.withIndex()) {
                (tool.jsonObject["functionDeclarations"] as? JsonArray)?.let { decls ->
                    for ((functionIndex, function) in decls.withIndex()) {
                        val paramsSchema = function.jsonObject["parametersJsonSchema"]?.jsonObject
                            ?: function.jsonObject["parameters"]?.jsonObject
                        paramsSchema?.let { params ->
                            span.setAttribute(
                                "gen_ai.tool.$index.function.$functionIndex.type",
                                params["type"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                        val name = function.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                        val description = function.jsonObject["description"]?.jsonPrimitive?.contentOrNull
                        val parameters = (function.jsonObject["parametersJsonSchema"] ?: function.jsonObject["parameters"])?.toString()
                        span.setAttribute(
                            "gen_ai.tool.$index.function.$functionIndex.name",
                            name?.orRedactedInput(),
                        )
                        span.setAttribute(
                            "gen_ai.tool.$index.function.$functionIndex.description",
                            description?.orRedactedInput(),
                        )
                        span.setAttribute(
                            "gen_ai.tool.$index.function.$functionIndex.parameters",
                            parameters?.orRedactedInput(),
                        )
                    }
                }
            }
        }

        cachedContent["systemInstruction"]?.let {
            span.setAttribute("tracy.request.system_instruction", it.toString().orRedactedInput())
        }
        cachedContent["toolConfig"]?.let {
            span.setAttribute("tracy.request.tool_config", it.toString())
        }
    }

    fun traceResponse(span: Span, cachedContent: JsonObject) {
        cachedContent["name"]?.jsonPrimitive?.contentOrNull?.let {
            // `name` (e.g. "cachedContents/abc123") doubles as the canonical resource id.
            span.setAttribute(GEN_AI_RESPONSE_ID, it)
            span.setAttribute("tracy.response.cached_content.name", it)
        }
        cachedContent["model"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute(GEN_AI_RESPONSE_MODEL, it)
        }
        cachedContent["displayName"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.cached_content.display_name", it)
        }
        cachedContent["createTime"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.cached_content.create_time", it)
        }
        cachedContent["updateTime"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.cached_content.update_time", it)
        }
        cachedContent["expireTime"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.cached_content.expire_time", it)
        }
        cachedContent["usageMetadata"]?.let {
            span.setAttribute("tracy.response.cached_content.usage_metadata", it.toString())
        }
    }

    fun traceListItem(span: Span, cachedContent: JsonObject, index: Int) {
        cachedContent["name"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.list.$index.name", it)
        }
        cachedContent["model"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.list.$index.model", it)
        }
        cachedContent["displayName"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.list.$index.display_name", it)
        }
    }

    /**
     * Extracts `text` from a `parts` array if it contains exactly one entry with a `text`
     * field. Returns `null` otherwise so callers fall back to serializing the array as JSON.
     */
    private fun JsonElement.singleTextMessageInParts(): String? {
        val parts = this
        if (parts !is JsonArray || parts.size != 1) return null
        val item = parts.first().jsonObject
        return if ("text" in item.keys) item["text"]?.jsonPrimitive?.contentOrNull else null
    }
}
