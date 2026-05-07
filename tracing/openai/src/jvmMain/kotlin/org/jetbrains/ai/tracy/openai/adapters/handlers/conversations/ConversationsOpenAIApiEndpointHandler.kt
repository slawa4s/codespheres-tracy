/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils

/**
 * Handler for OpenAI Conversations API.
 *
 * Traces conversation lifecycle requests — creation, retrieval, and message operations
 * on conversation threads.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler(
    @Suppress("UNUSED_PARAMETER") extractor: MediaContentExtractor
) : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        body["input"]?.let { input ->
            when (input) {
                is JsonArray -> {
                    for ((index, item) in input.withIndex()) {
                        item.jsonObject["role"]?.jsonPrimitive?.contentOrNull?.let {
                            span.setAttribute("gen_ai.prompt.$index.role", it)
                        }
                        item.jsonObject["content"]?.jsonPrimitive?.contentOrNull?.let {
                            span.setAttribute("gen_ai.prompt.$index.content", it.orRedactedInput())
                        }
                    }
                }
                is JsonPrimitive -> {
                    span.setAttribute("gen_ai.prompt.0.role", "user")
                    span.setAttribute("gen_ai.prompt.0.content", input.contentOrNull?.orRedactedInput())
                }
                else -> Unit
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonResponseAttributes(span, response)

        body["output"]?.let { output ->
            if (output is JsonArray) {
                for ((index, item) in output.withIndex()) {
                    item.jsonObject["role"]?.jsonPrimitive?.contentOrNull?.let {
                        span.setAttribute("gen_ai.completion.$index.role", it)
                    }
                    item.jsonObject["content"]?.jsonPrimitive?.contentOrNull?.let {
                        span.setAttribute("gen_ai.completion.$index.content", it.orRedactedOutput())
                    }
                    item.jsonObject["status"]?.jsonPrimitive?.contentOrNull?.let {
                        span.setAttribute("gen_ai.completion.$index.finish_reason", it)
                    }
                }
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
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Conversations API streaming is not currently supported" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
