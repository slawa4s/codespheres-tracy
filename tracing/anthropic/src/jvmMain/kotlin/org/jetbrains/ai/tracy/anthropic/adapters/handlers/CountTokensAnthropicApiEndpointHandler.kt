/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.handlers.sse.sseHandlingUnsupported
import org.jetbrains.ai.tracy.core.http.parsers.SseEvent
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput

/**
 * Handler for Anthropic Count Tokens API.
 *
 * Endpoint: `POST /v1/messages/count_tokens`
 *
 * Request body attributes set on the span:
 * - `gen_ai.request.model`            ← `model`
 * - `gen_ai.prompt.{i}.role`          ← `messages[i].role`
 * - `gen_ai.prompt.{i}.content`       ← `messages[i].content` (redacted via [orRedactedOutput])
 * - `gen_ai.request.system`           ← `system`
 * - `gen_ai.request.cache_control`    ← `cache_control` (serialized JSON object)
 * - `gen_ai.request.output_config`    ← `output_config` (serialized JSON object)
 * - `gen_ai.request.thinking`         ← `thinking` (serialized JSON object)
 * - `gen_ai.request.tool_choice`      ← `tool_choice` (serialized JSON object)
 * - `gen_ai.request.tools.count`      ← `tools.size` (long)
 * - `gen_ai.request.tools.{i}.tool`   ← `tools[i]` (serialized JSON object)
 *
 * Response attributes:
 * - `gen_ai.usage.input_tokens` ← top-level `input_tokens` field
 * - `gen_ai.response.id`        ← top-level `id` field (present on some proxies)
 *
 * See: [Count Tokens API](https://docs.anthropic.com/en/api/messages-count-tokens)
 */
internal class CountTokensAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "count_tokens")
        span.setAttribute(GEN_AI_OPERATION_NAME, "count_tokens")

        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }

        // messages: trace each message's role + content; content is redacted as sensitive output.
        (body["messages"] as? JsonArray)?.let { messages ->
            for ((index, element) in messages.withIndex()) {
                val message = element as? JsonObject ?: continue
                message["role"]?.jsonPrimitive?.content?.let {
                    span.setAttribute("gen_ai.prompt.$index.role", it)
                }
                message["content"]?.let {
                    span.setAttribute("gen_ai.prompt.$index.content", it.toString().orRedactedOutput())
                }
            }
        }

        // system: optional string
        body["system"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.system", it)
        }

        // Optional configuration objects — traced as opaque JSON blobs.
        body["cache_control"]?.let {
            span.setAttribute("gen_ai.request.cache_control", it.toString())
        }
        body["output_config"]?.let {
            span.setAttribute("gen_ai.request.output_config", it.toString())
        }
        body["thinking"]?.let {
            span.setAttribute("gen_ai.request.thinking", it.toString())
        }
        body["tool_choice"]?.let {
            span.setAttribute("gen_ai.request.tool_choice", it.toString())
        }

        // tools: trace count + each tool object serialized as JSON
        val tools = body["tools"]
        if (tools is JsonArray) {
            span.setAttribute("gen_ai.request.tools.count", tools.size.toLong())
            for ((index, tool) in tools.withIndex()) {
                span.setAttribute("gen_ai.request.tools.$index.tool", tool.toString().orRedactedOutput())
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
        }
        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_RESPONSE_ID, it)
        }
    }

    override fun handleStreamingEvent(
        span: Span,
        event: SseEvent,
        index: Long
    ): Result<Unit> {
        return sseHandlingUnsupported()
    }
}
