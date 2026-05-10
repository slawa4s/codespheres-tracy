/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.populateUnmappedAttributes
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.*
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.ContentKind
import org.jetbrains.ai.tracy.core.policy.contentTracingAllowed
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*
import kotlinx.serialization.json.*

/**
 * Handler for OpenAI Responses API
 */
internal class ResponsesOpenAIApiEndpointHandler(
    private val extractor: MediaContentExtractor
) : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject
        if (body == null) {
            handleGetRequestAttributes(span, request)
            return
        }
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        body["previous_response_id"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.request.previous_response_id", it)
            span.setAttribute("tracy.request.previous_response_id", it)
        }
        body["store"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("gen_ai.request.store", it)
            span.setAttribute("tracy.request.store", it)
        }
        body["top_p"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute(GEN_AI_REQUEST_TOP_P, it)
        }
        body["max_output_tokens"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute(GEN_AI_REQUEST_MAX_TOKENS, it)
        }
        body["parallel_tool_calls"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("gen_ai.request.parallel_tool_calls", it)
            span.setAttribute("tracy.request.parallel_tool_calls", it)
        }
        body["truncation"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.request.truncation", it)
            span.setAttribute("tracy.request.truncation", it)
        }
        body["service_tier"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("openai.request.service_tier", it)
        }
        body["stream"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("gen_ai.request.stream", it)
        }
        body["response_format"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute(GEN_AI_OUTPUT_TYPE, it)
        }
        body["tool_choice"]?.let {
            val content = when (it) {
                is JsonPrimitive -> it.content
                else -> it.toString()
            }
            span.setAttribute("gen_ai.request.tool_choice", content)
            span.setAttribute("tracy.request.tool_choice", content)
        }
        body["reasoning"]?.let { reasoning ->
            span.setAttribute("gen_ai.request.reasoning", reasoning.toString())
            if (reasoning is JsonObject) {
                reasoning["effort"]?.jsonPrimitive?.contentOrNull?.let {
                    span.setAttribute("tracy.request.reasoning.effort", it)
                }
                reasoning["summary"]?.jsonPrimitive?.contentOrNull?.let {
                    span.setAttribute("tracy.request.reasoning.summary", it)
                }
            }
        }
        body["text"]?.let {
            span.setAttribute("gen_ai.request.text", it.toString())
            (it as? JsonObject)?.get("format")?.let { fmt ->
                (fmt as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull?.let { type ->
                    span.setAttribute("tracy.request.text.format.type", type)
                }
            }
        }

        // because of inserting instructions property as the first prompt,
        // other input properties will have a position shifted by one
        val instructionsInsertedAsFirstPrompt: Boolean = body["instructions"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.prompt.0.content", it.orRedactedInput())
            span.setAttribute("gen_ai.prompt.0.role", "system")
            true
        } ?: false

        body["input"]?.let { inputs ->
            when (inputs) {
                is JsonArray -> {
                    parseRequestInputAttributes(span, inputs, instructionsInsertedAsFirstPrompt)
                    // attach media upload attributes only when content tracing is allowed
                    if (contentTracingAllowed(ContentKind.INPUT)) {
                        attachMediaContentAttributes(span, inputs)
                    }
                }

                else -> {
                    val index = if (instructionsInsertedAsFirstPrompt) 1 else 0
                    val content = when (inputs) {
                        is JsonPrimitive -> inputs.contentOrNull
                        else -> inputs.toString()
                    }
                    span.setAttribute("gen_ai.prompt.$index.role", "user")
                    span.setAttribute("gen_ai.prompt.$index.content", content?.orRedactedInput())
                }
            }
        }

        body["include"]?.let { include ->
            val value = when (include) {
                is JsonArray -> include.mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString(",")
                is JsonPrimitive -> include.content
                else -> null
            }
            value?.let { span.setAttribute("tracy.request.include", it) }
        }

        body["tools"]?.let { tools ->
            if (tools is JsonArray) {
                for ((index, tool) in tools.jsonArray.withIndex()) {
                    val toolType = tool.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    val toolName = tool.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    val toolDescription = tool.jsonObject["description"]?.jsonPrimitive?.contentOrNull
                    val toolParameters = tool.jsonObject["parameters"]?.jsonObject?.toString()
                    val strict = tool.jsonObject["strict"]?.jsonPrimitive?.boolean?.toString()

                    span.setAttribute("gen_ai.tool.$index.type", toolType)
                    span.setAttribute("gen_ai.tool.$index.name", toolName?.orRedactedInput())
                    span.setAttribute("gen_ai.tool.$index.description", toolDescription?.orRedactedInput())
                    span.setAttribute("gen_ai.tool.$index.parameters", toolParameters?.orRedactedInput())
                    span.setAttribute("gen_ai.tool.$index.strict", strict)

                    if (index == 0) {
                        toolType?.let { span.setAttribute("tracy.request.tool.type", it) }
                        toolName?.let { span.setAttribute("tracy.request.tool.name", it.orRedactedInput()) }
                        tool.jsonObject["search_context_size"]?.jsonPrimitive?.contentOrNull?.let {
                            span.setAttribute("tracy.request.tool.search_context_size", it)
                        }
                    }
                }
            }
        }

        span.populateUnmappedAttributes(body, mappedRequestAttributes, PayloadType.REQUEST)
    }

    private fun attachMediaContentAttributes(span: Span, inputs: JsonArray) {
        // set attributes with media attachments info into the span
        for (input in inputs) {
            val content = input.jsonObject["content"]
            if (content is JsonArray) {
                val mediaContent = parseMediaContent(content)
                extractor.setUploadableContentAttributes(span, field = "input", mediaContent)
            }
        }
    }

    /**
     * Handles GET requests to Responses API (e.g., input_items.list) where the body is empty
     * and parameters come from URL query parameters and path segments.
     */
    private fun handleGetRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setCommonRequestAttributes(span, request)
        val url = request.url
        val segments = url.pathSegments

        // Extract response_id from path: /responses/{response_id}/input_items
        val responsesIdx = segments.indexOf("responses")
        if (responsesIdx >= 0 && segments.size > responsesIdx + 1) {
            val responseId = segments[responsesIdx + 1]
            if (responseId.isNotBlank()) {
                span.setAttribute("tracy.request.response_id", responseId)
            }
        }

        val params = url.parameters
        params.queryParameter("limit")?.toLongOrNull()?.let { span.setAttribute("tracy.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
        params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        params.queryParameter("before")?.let { span.setAttribute("tracy.request.before", it) }

        // include may come as include[] (array-style) or include
        val includeValues = params.queryParameterValues("include[]").filterNotNull()
            .ifEmpty { params.queryParameterValues("include").filterNotNull() }
        if (includeValues.isNotEmpty()) {
            span.setAttribute("tracy.request.include", includeValues.joinToString(","))
        }
    }

    /**
     * Parses attributes from the Response Object of the Responses API.
     *
     * See [Response Object, Responses API](https://platform.openai.com/docs/api-reference/responses/object)
     */
    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonResponseAttributes(span, response)

        // Handle response.input_tokens responses (token counting endpoint returns top-level input_tokens)
        if (body["object"]?.jsonPrimitive?.content?.startsWith("response.input_tokens") == true) {
            body["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
            }
            span.populateUnmappedAttributes(body, listOf("input_tokens"), PayloadType.RESPONSE)
            return
        }

        // Handle list responses (e.g. input_items.list returns {"object":"list","data":[...]})
        if (body["object"]?.jsonPrimitive?.content == "list") {
            span.setAttribute("tracy.response.object", "list")
            body["data"]?.jsonArray?.let { data ->
                data.firstOrNull()?.jsonObject?.let { first ->
                    first["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.data.id", it) }
                    first["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.data.type", it) }
                }
            }
            body["first_id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.first_id", it) }
            body["last_id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.last_id", it) }
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.has_more", it) }
            return
        }

        // Parse error details when present (e.g. 400 responses)
        (body["error"] as? JsonObject)?.let { error ->
            error["message"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("tracy.response.error.message", it)
            }
            error["type"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("tracy.response.error.type", it)
            }
            error["code"]?.jsonPrimitive?.contentOrNull?.let {
                span.setAttribute("tracy.response.error.code", it)
            }
        }

        // we manually map `output` and `usage` attributes;
        // the rest of attributes get mapped by `populateUnmappedAttributes` below.
        (body["output"] as? JsonArray)?.let { outputs ->
            for ((index, output) in outputs.withIndex()) {
                when (val type = output.jsonObject["type"]?.jsonPrimitive?.content) {
                    "message", null -> {
                        // See schema: https://platform.openai.com/docs/api-reference/responses/object#responses-object-output-output_message
                        output.jsonObject["role"]?.jsonPrimitive?.content?.let {
                            span.setAttribute("gen_ai.completion.$index.role", it)
                        }
                        output.jsonObject["id"]?.jsonPrimitive?.content?.let {
                            span.setAttribute("gen_ai.completion.$index.id", it)
                        }
                        output.jsonObject["status"]?.jsonPrimitive?.content?.let {
                            span.setAttribute("gen_ai.completion.$index.finish_reason", it)
                        }

                        val content = output.jsonObject["content"]
                        // See schema: https://platform.openai.com/docs/api-reference/responses/object#responses-object-output-output_message-content
                        if (content is JsonArray) {
                            // if there is a single message that has a type of `output_text`, then install it as completion content;
                            // otherwise, set the entire array instead.
                            if (content.size == 1 && content.first().jsonObject["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                                val message = content
                                    .first { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
                                    .jsonObject

                                message["text"]?.jsonPrimitive?.content?.let {
                                    span.setAttribute(
                                        "gen_ai.completion.$index.content",
                                        it
                                    )
                                }
                                message["annotations"]?.let {
                                    span.setAttribute(
                                        "gen_ai.completion.$index.annotations",
                                        it.toString()
                                    )
                                }
                            } else {
                                // set the entire array as completion content
                                span.setAttribute("gen_ai.completion.$index.content", content.toString())
                            }
                        } else if (content != null) {
                            span.setAttribute("gen_ai.completion.$index.content", content.toString())
                        }
                    }

                    else -> {
                        // any other types, including 'function_call' and 'reasoning'
                        // See output types: https://platform.openai.com/docs/api-reference/responses/object#responses-object-output
                        for ((k, v) in output.jsonObject.entries) {
                            val key = when {
                                // prefix `function_call` with "tool_"
                                type == "function_call" && k == "type" -> "tool_call_type"
                                type == "function_call" -> "tool_$k"
                                // special treatment for content of `reasoning`
                                type == "reasoning" && k == "content" -> "output_content"
                                // special treatment of `type` field
                                k == "type" -> "output_type"
                                else -> k
                            }
                            val value = when {
                                v is JsonPrimitive -> v.content
                                else -> v.toString()
                            }
                            span.setAttribute("gen_ai.completion.$index.$key", value.orRedactedOutput())
                        }

                        // convenience attrs for first non-message output item
                        if (index == 0) {
                            type?.let { span.setAttribute("tracy.response.output.type", it) }
                            output.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.let {
                                span.setAttribute("tracy.response.output.name", it.orRedactedOutput())
                            }
                            output.jsonObject["call_id"]?.jsonPrimitive?.contentOrNull?.let {
                                span.setAttribute("tracy.response.output.call_id", it)
                            }
                        }
                    }
                }
            }
        }

        (body["usage"] as? JsonObject)?.let { usage ->
            setUsageAttributes(span, usage)
            usage["output_tokens_details"]?.jsonObject?.let { details ->
                details["reasoning_tokens"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("tracy.response.usage.output_tokens_details.reasoning_tokens", it.toLong())
                }
            }
            usage["input_tokens_details"]?.jsonObject?.let { details ->
                details["cached_tokens"]?.jsonPrimitive?.intOrNull?.let {
                    span.setAttribute("gen_ai.usage.cache_read.input_tokens", it.toLong())
                }
            }
        }

        body["service_tier"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("openai.response.service_tier", it)
        }

        body["reasoning"]?.let { reasoning ->
            if (reasoning is JsonObject) {
                reasoning["effort"]?.jsonPrimitive?.contentOrNull?.let {
                    span.setAttribute("tracy.response.reasoning.effort", it)
                }
                reasoning["summary"]?.jsonPrimitive?.contentOrNull?.let {
                    span.setAttribute("tracy.response.reasoning.summary", it)
                }
            }
        }

        // Extract text.format.type from response body
        (body["text"] as? JsonObject)?.get("format")?.let { fmt ->
            (fmt as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull?.let { type ->
                span.setAttribute("tracy.response.text.format.type", type)
            }
        }

        span.populateUnmappedAttributes(body, mappedResponseAttributes, PayloadType.RESPONSE)
    }

    override fun handleStreaming(span: Span, events: String): Unit = runCatching {
        for (line in events.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()

            val event = runCatching {
                Json.parseToJsonElement(data).jsonObject
            }.getOrNull() ?: continue

            val type = event["type"]?.jsonPrimitive?.content

            when (type) {
                "response.output_text.done" -> {
                    event["text"]?.jsonPrimitive?.content?.let {
                        span.setAttribute("gen_ai.completion.0.content", it.orRedactedOutput())
                        span.setAttribute("gen_ai.completion.0.finish_reason", "stop")
                    }
                }
                "response.done", "response.created", "response.completed" -> {
                    val responseObj = event["response"]?.jsonObject ?: event
                    responseObj["id"]?.jsonPrimitive?.contentOrNull?.let {
                        span.setAttribute(GEN_AI_RESPONSE_ID, it)
                    }
                    responseObj["model"]?.jsonPrimitive?.contentOrNull?.let {
                        span.setAttribute(GEN_AI_RESPONSE_MODEL, it)
                    }
                    responseObj["object"]?.jsonPrimitive?.contentOrNull?.let {
                        span.setAttribute("tracy.response.object", it)
                    }
                    responseObj["status"]?.jsonPrimitive?.contentOrNull?.let {
                        span.setAttribute("tracy.response.status", it)
                    }
                    responseObj["created_at"]?.jsonPrimitive?.longOrNull?.let {
                        span.setAttribute("tracy.response.created_at", it)
                    }
                    responseObj["completed_at"]?.jsonPrimitive?.longOrNull?.let {
                        span.setAttribute("tracy.response.completed_at", it)
                    }
                    val usageObj = responseObj["usage"]?.jsonObject ?: event["usage"]?.jsonObject
                    usageObj?.let { usage ->
                        usage["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
                            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
                        }
                        usage["output_tokens"]?.jsonPrimitive?.intOrNull?.let {
                            span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it)
                        }
                    }
                }
            }
        }
    }.getOrElse { exception ->
        span.setStatus(StatusCode.ERROR)
        span.recordException(exception)
    }

    /**
     * Parses input field of the request when it is of an array type.
     *
     * [instructionsInsertedAsFirstPrompt] indicates whether the "instructions" property
     * is present in the request.
     * If so, we shift all properties to be inserted into the span by one position,
     * i.e., indexing starts with 1 instead of 0.
     *
     * See the [schema](https://platform.openai.com/docs/api-reference/responses/create#responses_create-input)
     */
    private fun parseRequestInputAttributes(
        span: Span,
        inputs: JsonArray,
        instructionsInsertedAsFirstPrompt: Boolean,
    ) {
        val offset = if (instructionsInsertedAsFirstPrompt) 1 else 0

        for ((index, input) in inputs.withIndex()) {
            // when instructions were inserted, indices of other input messages
            // should be shifted by 1 ahead
            val position = index + offset

            // See: https://platform.openai.com/docs/api-reference/responses/create#responses_create-input
            when (val type = input.jsonObject["type"]?.jsonPrimitive?.content) {
                "message", null -> {
                    // this message can be either:
                    //   1. Input message: https://platform.openai.com/docs/api-reference/responses/create#responses_create-input-input_item_list-item-input_message
                    //   2. Output message: https://platform.openai.com/docs/api-reference/responses/create#responses_create-input-input_item_list-item-output_message
                    // the difference is in the `role` and `content` fields

                    // install primitive keys common for both input and output messages
                    val fields = listOf("id", "role", "status", "type")
                    for (field in fields) {
                        input.jsonObject[field]?.jsonPrimitive?.content?.let { value ->
                            val key = when (field) {
                                "type" -> "input_type"
                                else -> field
                            }
                            span.setAttribute("gen_ai.prompt.$position.$key", value)
                        }
                    }

                    val content = input.jsonObject["content"]
                    if (content is JsonArray) {
                        // if there is a single message that has a type of `input_text`, then install it as prompt content;
                        // otherwise, set the entire array instead.
                        if (content.size == 1 && content.first().jsonObject["type"]?.jsonPrimitive?.content == "input_text") {
                            val message = content
                                .first { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "input_text" }
                                .jsonObject

                            message["text"]?.jsonPrimitive?.content?.let {
                                span.setAttribute("gen_ai.prompt.$position.content", it.orRedactedInput())
                            }
                            message["type"]?.jsonPrimitive?.content?.let {
                                span.setAttribute("gen_ai.prompt.$position.content_type", it)
                            }
                        } else {
                            // set the entire array as prompt content
                            span.setAttribute("gen_ai.prompt.$position.content", content.toString().orRedactedInput())
                        }

                        // extract content type and type-specific fields from non-text content parts
                        for (partElement in content) {
                            val partObj = partElement as? JsonObject ?: continue
                            val contentType = partObj["type"]?.jsonPrimitive?.contentOrNull ?: continue
                            if (contentType == "input_text") continue
                            span.setAttribute("tracy.request.input.content.type", contentType)
                            when (contentType) {
                                "input_file" -> {
                                    partObj["file_id"]?.jsonPrimitive?.contentOrNull?.let {
                                        span.setAttribute("tracy.request.input.file.id", it)
                                    }
                                    partObj["filename"]?.jsonPrimitive?.contentOrNull?.let {
                                        span.setAttribute("tracy.request.input.file.filename", it)
                                    }
                                }
                                "input_image" -> {
                                    partObj["detail"]?.jsonPrimitive?.contentOrNull?.let {
                                        span.setAttribute("tracy.request.input.image.detail", it)
                                    }
                                }
                            }
                        }
                    } else if (content != null) {
                        val value = when (content) {
                            is JsonPrimitive -> content.contentOrNull
                            else -> content.toString()
                        }
                        span.setAttribute("gen_ai.prompt.$position.content", value?.orRedactedInput())
                    }
                }

                else -> {
                    // any other types, including 'function_call_output' and 'reasoning'
                    // See input types: https://platform.openai.com/docs/api-reference/responses/create#responses_create-input-input_item_list-item
                    val functionCallTypes = listOf("function_call", "function_call_output")
                    for ((k, v) in input.jsonObject.entries) {
                        val key = when {
                            // prefix `function_call`/`function_call_output` with "tool_"
                            (type in functionCallTypes) && k == "type" -> "tool_call_type"
                            (type in functionCallTypes) && k == "output" -> "output"
                            type in functionCallTypes -> "tool_$k"
                            // special treatment for content of `reasoning`
                            type == "reasoning" && k == "content" -> "output_content"
                            // special treatment of `type` field
                            k == "type" -> "output_type"
                            else -> k
                        }
                        val value = when {
                            v is JsonPrimitive -> v.content
                            else -> v.toString()
                        }
                        span.setAttribute("gen_ai.prompt.$position.$key", value.orRedactedInput())
                    }
                }
            }
        }
    }

    /**
     * Sets usage attributes (input_tokens/output_tokens)
     */
    private fun setUsageAttributes(span: Span, usage: JsonObject) {
        usage["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
        }
        usage["output_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it)
        }
    }

    /**
     * Extracts media content parts (images, files) from JSON content.
     *
     * See details: [Responses API](https://platform.openai.com/docs/api-reference/responses/create)
     */
    private fun parseMediaContent(content: JsonArray): MediaContent {
        val parts = buildList {
            for (part in content) {
                val type = part.jsonObject["type"]?.jsonPrimitive?.content ?: continue

                val mediaPart = when (type) {
                    "input_image" -> {
                        val url = part.jsonObject["image_url"]?.jsonPrimitive?.content ?: continue
                        when {
                            url.isValidUrl() -> MediaContentPart(Resource.Url(url))
                            url.startsWith("data:") -> MediaContentPart(Resource.InlineDataUrl(url))
                            else -> null
                        }
                    }

                    "input_file" -> when {
                        "file_url" in part.jsonObject -> {
                            val url = part.jsonObject["file_url"]?.jsonPrimitive?.content ?: continue
                            if (url.isValidUrl()) MediaContentPart(Resource.Url(url)) else null
                        }

                        "file_data" in part.jsonObject -> {
                            val dataUrl = part.jsonObject["file_data"]?.jsonPrimitive?.content ?: continue
                            MediaContentPart(Resource.InlineDataUrl(dataUrl))
                        }

                        else -> null
                    }

                    else -> null
                }

                // if the media part is valid, append it to the list
                if (mediaPart != null) {
                    add(mediaPart)
                }
            }
        }

        return MediaContent(parts)
    }

    // https://platform.openai.com/docs/api-reference/responses/create
    private val mappedRequestAttributes: List<String> = listOf(
        "temperature",
        "model",
        "previous_response_id",
        "store",
        "top_p",
        "max_output_tokens",
        "truncation",
        "parallel_tool_calls",
        "stream",
        "response_format",
        "tool_choice",
        "reasoning",
        "text",
        "input",
        "instructions",
        "tools",
        "service_tier",
        "include",
    )

    // https://platform.openai.com/docs/api-reference/responses/object
    private val mappedResponseAttributes: List<String> = listOf(
        // parsed by `OpenAIApiUtils.setCommonResponseAttributes`
        "id",
        "model",

        "output",
        "usage",
        "service_tier",
        "reasoning",
    )
}
