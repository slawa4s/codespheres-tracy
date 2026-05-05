/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.populateUnmappedAttributes
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS
import kotlinx.serialization.json.*

/**
 * Handler for OpenAI Conversations API.
 *
 * See: https://platform.openai.com/docs/api-reference/conversations
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        body["instructions"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.request.instructions", it.orRedactedInput())
        }

        body["messages"]?.let { messages ->
            for ((index, message) in messages.jsonArray.withIndex()) {
                val role = message.jsonObject["role"]?.jsonPrimitive?.content
                val rawContent = message.jsonObject["content"]
                val content = when (rawContent) {
                    is JsonPrimitive -> rawContent.contentOrNull
                    else -> rawContent?.toString()
                }
                span.setAttribute("gen_ai.prompt.$index.role", role)
                span.setAttribute("gen_ai.prompt.$index.content", content?.orRedactedInput())
            }
        }

        span.populateUnmappedAttributes(body, mappedAttributes, PayloadType.REQUEST)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonResponseAttributes(span, response)

        body["output"]?.let { outputs ->
            for ((index, output) in outputs.jsonArray.withIndex()) {
                val role = output.jsonObject["role"]?.jsonPrimitive?.content
                val rawContent = output.jsonObject["content"]
                val content = when (rawContent) {
                    is JsonPrimitive -> rawContent.contentOrNull
                    else -> rawContent?.toString()
                }
                span.setAttribute("gen_ai.completion.$index.role", role)
                span.setAttribute("gen_ai.completion.$index.content", content?.orRedactedOutput())
            }
        }

        body["usage"]?.jsonObject?.let { usage ->
            usage["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
            }
            usage["output_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it)
            }
        }

        span.populateUnmappedAttributes(body, mappedAttributes, PayloadType.RESPONSE)
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use streaming
    }

    private val mappedAttributes: List<String> = listOf(
        "model",
        "temperature",
        "instructions",
        "messages",
        "output",
        "usage",
        // parsed by OpenAIApiUtils.setCommonResponseAttributes
        "id",
        "object",
    )
}
