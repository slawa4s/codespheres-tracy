/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API manages stateful chat conversations. Its response objects carry an
 * `"object"` field of `"conversation"` or `"conversation.deleted"`, which
 * [OpenAIApiUtils.setCommonResponseAttributes] would naively promote to [GEN_AI_OPERATION_NAME].
 * Those values are not valid operation names, so this handler overrides the attribute
 * unconditionally with `"chat"` — the correct OpenTelemetry GenAI semantic convention value for
 * chat-based operations.
 *
 * See: [OpenAI Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler(
    @Suppress("unused") private val extractor: MediaContentExtractor
) : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setCommonRequestAttributes(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // setCommonResponseAttributes() (called before this method in
        // OpenAILLMTracingAdapter.getResponseBodyAttributes()) maps the response "object" field
        // to GEN_AI_OPERATION_NAME. For the Conversations API that field is "conversation" or
        // "conversation.deleted", neither of which is a valid operation name. Override it
        // unconditionally so this value always wins.
        span.setAttribute(GEN_AI_OPERATION_NAME, "chat")

        val body = response.body.asJson()?.jsonObject ?: return

        body["usage"]?.let { usage ->
            usage.jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
            }
            usage.jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it)
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use server-sent events streaming
    }
}
