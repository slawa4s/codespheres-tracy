/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
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
 * Parses native Gemini `embedContent` API requests and responses.
 *
 * Endpoint shape:
 *   `POST https://generativelanguage.googleapis.com/v1beta/{model}:embedContent`,
 *   where `{model}` follows the `models/{name}` pattern.
 *
 * The Vertex AI predict layout for embedding models is handled by
 * [GeminiVertexEmbedHandler] — the adapter dispatches based on the URL.
 *
 * ## Request attribute mapping
 *
 * | Source                  | OTel attribute                          | Redaction         |
 * |-------------------------|-----------------------------------------|-------------------|
 * | `content.parts`         | `gen_ai.prompt.0.content`               | `orRedactedInput` |
 * | `taskType` (deprecated) | `gen_ai.request.task_type`              | none              |
 * | `title` (deprecated)    | `gen_ai.request.title`                  | `orRedactedInput` |
 * | `outputDimensionality` (deprecated) | `gen_ai.request.output_dimensionality` | none |
 * | `embedContentConfig`    | `gen_ai.request.embed_content_config`   | none — serialized JSON as-is |
 *
 * Deprecated fields are still traced for compatibility with older SDK / API consumers.
 *
 * ## Response attribute mapping
 *
 * | Source                                   | OTel attribute                                          | Redaction          |
 * |------------------------------------------|---------------------------------------------------------|--------------------|
 * | `embedding.values` (array)               | `gen_ai.response.embedding.values`                      | `orRedactedOutput` |
 * | `embedding.values.length`                | `gen_ai.response.embedding.dimension`                   | none               |
 * | `embedding.shape`                        | `gen_ai.response.embedding.shape` (JSON array string)   | none               |
 * | `usageMetadata.promptTokenCount`         | `gen_ai.usage.input_tokens`                             | none               |
 * | `usageMetadata.promptTokenDetails[i].modality`   | `gen_ai.usage.prompt_token_details.{i}.modality`    | none           |
 * | `usageMetadata.promptTokenDetails[i].tokenCount` | `gen_ai.usage.prompt_token_details.{i}.token_count` | none           |
 *
 * See: [Embed Content API](https://ai.google.dev/api/embeddings#v1beta.models.embedContent)
 */
internal class GeminiEmbedHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        span.setAttribute("gemini.api.type", "models")

        // taskType: enum string identifying the retrieval / classification / clustering / etc. task.
        body["taskType"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.request.task_type", it)
        }

        // outputDimensionality: optional override of the embedding vector size.
        body["outputDimensionality"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.request.output_dimensionality", it.toLong())
        }

        // Prompt content: content = { "parts": [{ "text": str }, ...] }. Mirror the
        // GeminiContentGenHandler convention — install raw text when there's a single text
        // part; otherwise serialize the parts array.
        body["content"]?.jsonObject?.get("parts")?.jsonArray?.let { parts ->
            val singleText = parts.singleOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            if (singleText != null) {
                span.setAttribute("gen_ai.prompt.0.content", singleText.orRedactedInput())
            } else {
                span.setAttribute("gen_ai.prompt.0.content", parts.toString().orRedactedInput())
            }
        }

        // title: optional, used for retrieval-style task types. Treated as input.
        body["title"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.request.title", it.orRedactedInput())
        }

        // embedContentConfig: opaque config object; trace as-is (serialized JSON) per the
        // API spec. May redundantly carry task_type/title/outputDimensionality on newer SDKs.
        body["embedContentConfig"]?.jsonObject?.let {
            span.setAttribute("gen_ai.request.embed_content_config", it.toString())
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        span.setAttribute(GEN_AI_OUTPUT_TYPE, "embedding")

        body["embedding"]?.jsonObject?.let { embedding ->
            embedding["values"]?.jsonArray?.let { values ->
                span.setAttribute("gen_ai.response.embedding.values", values.toString().orRedactedOutput())
                span.setAttribute("gen_ai.response.embedding.dimension", values.size.toLong())
            }
            embedding["shape"]?.jsonArray?.let { shape ->
                span.setAttribute("gen_ai.response.embedding.shape", shape.toString())
            }
        }

        body["usageMetadata"]?.jsonObject?.let { usage ->
            usage["promptTokenCount"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it.toLong())
            }
            usage["promptTokenDetails"]?.jsonArray?.forEachIndexed { index, detail ->
                val obj = detail.jsonObject
                obj["modality"]?.jsonPrimitive?.contentOrNull?.let {
                    span.setAttribute("gen_ai.usage.prompt_token_details.$index.modality", it)
                }
                obj["tokenCount"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.usage.prompt_token_details.$index.token_count", it.toLong())
                }
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
