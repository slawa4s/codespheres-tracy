/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.moderations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils

/**
 * Handler for the OpenAI Moderations API.
 *
 * Classifies whether text or images are potentially harmful.
 *
 * See [Moderations API Reference](https://platform.openai.com/docs/api-reference/moderations)
 */
internal class ModerationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setNetworkRequestAttributes(span, request)
        span.setAttribute("openai.api.type", "moderations")
        span.setAttribute(GEN_AI_OPERATION_NAME, "moderations")

        val body = request.body.asJson()?.jsonObject ?: return
        val inputField = body["input"]
        val inputType = when (inputField) {
            is JsonPrimitive -> "string"
            is JsonArray -> "array"
            else -> return
        }
        span.setAttribute("tracy.request.input.type", inputType)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setHttpStatusCode(span, response)

        val body = response.body.asJson()?.jsonObject ?: return
        val results = body["results"]?.jsonArray ?: return

        span.setAttribute("tracy.response.results.count", results.size.toLong())

        val first = results.getOrNull(0)?.jsonObject ?: return
        first["flagged"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.results.flagged", it)
        }
        first["categories"]?.jsonObject?.let {
            span.setAttribute("tracy.response.results.categories", it.toString())
        }
        first["category_scores"]?.jsonObject?.let {
            span.setAttribute("tracy.response.results.category_scores", it.toString())
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Moderations API does not use server-sent events streaming
        logger.warn { "Moderations API does not use server-sent events streaming" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
