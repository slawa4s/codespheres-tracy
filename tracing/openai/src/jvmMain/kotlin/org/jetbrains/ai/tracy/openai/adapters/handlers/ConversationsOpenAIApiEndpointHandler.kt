/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
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

/**
 * Handler for OpenAI Conversations API.
 *
 * Traces requests and responses for the `/v1/conversations` endpoint family.
 *
 * See: [OpenAI API Reference](https://platform.openai.com/docs/api-reference)
 */
internal class ConversationsOpenAIApiEndpointHandler(
    @Suppress("unused") private val extractor: MediaContentExtractor
) : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        body["messages"]?.let { messages ->
            for ((index, message) in messages.jsonArray.withIndex()) {
                val role = message.jsonObject["role"]?.jsonPrimitive?.content
                val content = message.jsonObject["content"]?.jsonPrimitive?.content

                span.setAttribute("gen_ai.prompt.$index.role", role)
                span.setAttribute("gen_ai.prompt.$index.content", content?.orRedactedInput())
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonResponseAttributes(span, response)

        body["output"]?.let { output ->
            for ((index, item) in output.jsonArray.withIndex()) {
                val role = item.jsonObject["role"]?.jsonPrimitive?.content
                val content = item.jsonObject["content"]?.jsonPrimitive?.content

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
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.debug { "Conversations API streaming is not yet supported" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
