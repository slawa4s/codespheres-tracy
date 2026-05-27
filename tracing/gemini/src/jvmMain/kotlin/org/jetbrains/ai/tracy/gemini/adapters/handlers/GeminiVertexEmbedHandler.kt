/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.handlers.sse.sseHandlingUnsupported
import org.jetbrains.ai.tracy.core.http.parsers.SseEvent
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput

/**
 * Parses Vertex AI predict requests/responses for embedding models.
 *
 * The Vertex AI Embeddings API uses the generic `predict` endpoint, distinguished from other
 * predict callers (e.g. Imagen) by the model name containing `embedding` (selection happens
 * upstream in the adapter).
 *
 * Endpoint shape:
 *   `POST https://{REGION}-aiplatform.googleapis.com/v1/projects/{P}/locations/{R}/publishers/google/models/{MODEL}:predict`
 *
 * ## Request attribute mapping (from `instances[0]` and `parameters`)
 *
 * | Source                              | OTel attribute                              | Redaction         |
 * |-------------------------------------|---------------------------------------------|-------------------|
 * | `instances[0].content`              | `gen_ai.prompt.0.content`                   | `orRedactedInput` |
 * | `instances[0].title`                | `gen_ai.request.title`                      | `orRedactedInput` |
 * | `instances[0].task_type`            | `gen_ai.request.task_type`                  | none              |
 * | `parameters.autoTruncate`           | `gen_ai.request.auto_truncate`              | none              |
 * | `parameters.outputDimensionality`   | `gen_ai.request.output_dimensionality`      | none              |
 *
 * ## Response attribute mapping (from `predictions[0].embeddings`)
 *
 * | Source                                              | OTel attribute                                     | Redaction          |
 * |-----------------------------------------------------|----------------------------------------------------|--------------------|
 * | `predictions[0].embeddings.values`                  | `gen_ai.response.embedding.values`                 | `orRedactedOutput` |
 * | `predictions[0].embeddings.values.length`           | `gen_ai.response.embedding.dimension`              | none               |
 * | `predictions[0].embeddings.statistics.truncated`    | `gen_ai.response.embedding.statistics.truncated`   | none               |
 * | `predictions[0].embeddings.statistics.token_count`  | `gen_ai.response.embedding.statistics.token_count` + `gen_ai.usage.input_tokens` | none |
 *
 * Only the first `instances` / `predictions` element is traced. The Vertex API allows batch
 * arrays, but full batch coverage is out of scope here.
 *
 * See: [Vertex AI Text Embeddings API](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/model-reference/text-embeddings-api)
 */
internal class GeminiVertexEmbedHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        span.setAttribute("gemini.api.type", "models")

        val firstInstance = body["instances"]?.jsonArray?.firstOrNull()?.jsonObject
        firstInstance?.let { instance ->
            instance["content"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.prompt.0.content", it.orRedactedInput())
            }
            instance["title"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.request.title", it.orRedactedInput())
            }
            instance["task_type"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("gen_ai.request.task_type", it)
            }
        }

        body["parameters"]?.jsonObject?.let { parameters ->
            parameters["autoTruncate"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.request.auto_truncate", it)
            }
            parameters["outputDimensionality"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.request.output_dimensionality", it.toLong())
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        span.setAttribute(GEN_AI_OUTPUT_TYPE, "embedding")

        val embeddings = body["predictions"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("embeddings")?.jsonObject ?: return

        embeddings["values"]?.jsonArray?.let { values ->
            span.setAttribute("gen_ai.response.embedding.values", values.toString().orRedactedOutput())
            span.setAttribute("gen_ai.response.embedding.dimension", values.size.toLong())
        }

        embeddings["statistics"]?.jsonObject?.let { stats ->
            stats["truncated"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.embedding.statistics.truncated", it)
            }
            stats["token_count"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("gen_ai.response.embedding.statistics.token_count", it.toLong())
                // Cross-format equivalent of `usageMetadata.promptTokenCount` in the native API.
                span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it.toLong())
            }
        }
    }

    override fun handleStreamingEvent(
        span: Span,
        event: SseEvent,
        index: Long,
    ): Result<Unit> {
        return sseHandlingUnsupported()
    }
}
