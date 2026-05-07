/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.populateUnmappedAttributes
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.ContentKind
import org.jetbrains.ai.tracy.core.policy.orRedacted
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Conversations API.
 *
 * Handles tracing for /v1/conversations/ endpoints including creating conversations,
 * listing conversations, retrieving conversations, and managing messages within conversations.
 *
 * See: [OpenAI Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler(
    @Suppress("UNUSED_PARAMETER") extractor: MediaContentExtractor
) : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        body["messages"]?.let { messages ->
            for ((index, message) in messages.jsonArray.withIndex()) {
                val role = message.jsonObject["role"]?.jsonPrimitive?.content
                val content = message.jsonObject["content"]?.jsonPrimitive?.content
                val kind = kindByRole(role)

                span.setAttribute("gen_ai.prompt.$index.role", role)
                span.setAttribute("gen_ai.prompt.$index.content", content?.orRedacted(kind))
            }
        }

        body["instructions"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.instructions", it.orRedactedInput())
        }

        span.populateUnmappedAttributes(body, mappedAttributes, PayloadType.REQUEST)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.id", it)
        }
        body["object"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.object", it)
        }
        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.model", it)
        }
        body["status"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.status", it)
        }

        body["output"]?.let { outputs ->
            if (outputs is JsonArray) {
                for ((index, output) in outputs.withIndex()) {
                    val role = output.jsonObject["role"]?.jsonPrimitive?.content
                    val content = output.jsonObject["content"]?.jsonPrimitive?.content
                    val finishReason = output.jsonObject["finish_reason"]?.jsonPrimitive?.content

                    span.setAttribute("gen_ai.completion.$index.role", role)
                    span.setAttribute("gen_ai.completion.$index.content", content?.orRedactedOutput())
                    span.setAttribute("gen_ai.completion.$index.finish_reason", finishReason)
                }
            }
        }

        body["usage"]?.let { usage ->
            setUsageAttributes(span, usage.jsonObject)
        }

        span.populateUnmappedAttributes(body, mappedAttributes, PayloadType.RESPONSE)
    }

    override fun handleStreaming(span: Span, events: String): Unit = runCatching {
        var role: String? = null
        val out = buildString {
            for (line in events.lineSequence()) {
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()

                val event = runCatching {
                    Json.parseToJsonElement(data).jsonObject
                }.getOrNull() ?: continue

                val delta = event["delta"]?.jsonObject ?: continue

                if (role == null) {
                    role = delta["role"]?.jsonPrimitive?.content
                }
                delta["content"]?.jsonPrimitive?.content?.let { append(it) }
            }
        }

        if (out.isNotEmpty()) {
            val kind = kindByRole(role)
            span.setAttribute("gen_ai.completion.0.content", out.orRedacted(kind))
        }
        role?.let { span.setAttribute("gen_ai.completion.0.role", it) }

        return@runCatching
    }.getOrElse { exception ->
        span.setStatus(StatusCode.ERROR)
        span.recordException(exception)
    }

    private fun kindByRole(role: String?): ContentKind = when (role) {
        "system", "user", "developer" -> ContentKind.INPUT
        else -> ContentKind.OUTPUT
    }

    private fun setUsageAttributes(span: Span, usage: JsonObject) {
        usage["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
        }
        usage["output_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it)
        }
        // also handle prompt_tokens/completion_tokens naming
        usage["prompt_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
        }
        usage["completion_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it)
        }
    }

    // https://platform.openai.com/docs/api-reference/conversations
    private val mappedRequestAttributes: List<String> = listOf(
        "model",
        "messages",
        "instructions",
        "temperature",
    )

    // https://platform.openai.com/docs/api-reference/conversations
    private val mappedResponseAttributes: List<String> = listOf(
        "id",
        "object",
        "model",
        "status",
        "output",
        "usage",
    )

    private val mappedAttributes = mappedRequestAttributes + mappedResponseAttributes
}
