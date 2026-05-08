/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*
import kotlinx.serialization.json.*

/**
 * Common utilities for OpenAI API handling
 */
internal object OpenAIApiUtils {

    /**
     * Sets common request attributes (temperature, model)
     */
    fun setCommonRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["temperature"]?.let { span.setAttribute(GEN_AI_REQUEST_TEMPERATURE, it.jsonPrimitive.doubleOrNull) }
        body["model"]?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.jsonPrimitive.content) }
    }

    /**
     * Sets common response attributes (id, model, object type)
     */
    fun setCommonResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
        body["object"]?.let { span.setAttribute(GEN_AI_OPERATION_NAME, it.jsonPrimitive.content) }
        body["model"]?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it.jsonPrimitive.content) }
    }

    /**
     * Sets network-level request attributes: provider name, server address and port.
     *
     * These attributes are the OpenTelemetry stable semconv equivalents of the
     * `gen_ai.system` / `gen_ai.api_base` pair emitted by the base [org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter].
     */
    fun setNetworkRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gen_ai.provider.name", "openai")
        span.setAttribute("server.address", request.url.host)
        val port = if (request.url.scheme == "https") 443L else 80L
        span.setAttribute("server.port", port)
    }

    /**
     * Sets the HTTP response status code using the stable semconv attribute name
     * (`http.response.status_code`), complementing the legacy `http.status_code`
     * attribute set by the base [org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter].
     */
    fun setHttpStatusCode(span: Span, response: TracyHttpResponse) {
        span.setAttribute("http.response.status_code", response.code.toLong())
    }
}

internal val JsonElement.asString: String
    get() = when (this) {
        is JsonArray -> this.jsonArray.toString()
        is JsonObject -> this.jsonObject.toString()
        is JsonPrimitive -> this.jsonPrimitive.content
    }