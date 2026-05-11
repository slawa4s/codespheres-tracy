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
        val body = request.body.asJson()?.jsonObject ?: run {
            // empty body for GET/DELETE requests — still set operation name and api type
            span.setAttribute("openai.api.type", "responses")
            span.setAttribute("gen_ai.operation.name", resolveResponsesOperationName(request.url.pathSegments, request.method))
            val params = request.url.parameters
            // OpenAI SDK serializes include as "include[]" query parameter; fall back to "include"
            val includeParam = params.queryParameter("include[]") ?: params.queryParameter("include")
            includeParam?.let { span.setAttribute("tracy.request.include", it) }
            // Extract response_id from path for all responses requests
            Companion.extractResponseIdFromPath(request.url.pathSegments)?.let { span.setAttribute("tracy.request.response_id", it) }
            // For input_items list endpoints, also extract pagination params
            if (request.url.pathSegments.contains("input_items")) {
                params.queryParameter("limit")?.toLongOrNull()?.let { span.setAttribute("tracy.request.limit", it) }
                params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
                params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
            }
            return
        }

        span.setAttribute("openai.api.type", "responses")
        span.setAttribute("gen_ai.operation.name", resolveResponsesOperationName(request.url.pathSegments, request.method))

        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        body["previous_response_id"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.request.previous_response_id", it)
        }
        body["store"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.request.store", it)
        }
        body["background"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.request.background", it)
        }
        body["top_p"]?.jsonPrimitive?.doubleOrNull?.let {
            span.setAttribute(GEN_AI_REQUEST_TOP_P, it)
        }
        body["max_output_tokens"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute(GEN_AI_REQUEST_MAX_TOKENS, it)
        }
        body["truncation"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.request.truncation", it)
        }
        body["parallel_tool_calls"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.request.parallel_tool_calls", it)
        }
        body["stream"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("gen_ai.request.stream", it)
        }
        body["response_format"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute(GEN_AI_OUTPUT_TYPE, it)
        }
        body["service_tier"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("openai.request.service_tier", it)
        }
        body["tool_choice"]?.let {
            val content = when (it) {
                is JsonPrimitive -> it.content
                else -> it.toString()
            }
            span.setAttribute("tracy.request.tool_choice", content)
        }
        body["reasoning"]?.let {
            span.setAttribute("gen_ai.request.reasoning", it.toString())
        }
        (body["reasoning"] as? JsonObject)?.let { reasoning ->
            reasoning["effort"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.reasoning.effort", it) }
            reasoning["summary"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.reasoning.summary", it) }
        }
        body["text"]?.let {
            span.setAttribute("gen_ai.request.text", it.toString())
        }
        ((body["text"] as? JsonObject)?.get("format") as? JsonObject)?.let { fmt ->
            fmt["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.request.text.format.type", it) }
        }

        body["include"]?.let {
            when (it) {
                is JsonArray -> span.setAttribute("tracy.request.include", it.joinToString(",") { e -> e.jsonPrimitive.content })
                is JsonPrimitive -> span.setAttribute("tracy.request.include", it.content)
                else -> {}
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

        span.populateUnmappedAttributes(body, mappedAttributes, PayloadType.REQUEST)
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
     * Parses attributes from the Response Object of the Responses API.
     *
     * See [Response Object, Responses API](https://platform.openai.com/docs/api-reference/responses/object)
     */
    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // Explicitly set tracy.response.object so it doesn't get swallowed by mappedAttributes
        body["object"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.object", it)
        }
        // Fix operation name for input token count responses
        if (body["object"]?.jsonPrimitive?.contentOrNull == "response.input_tokens") {
            span.setAttribute("gen_ai.operation.name", "response.input_tokens.count")
        }
        // Set usage for non-output responses (e.g., input_tokens count)
        body["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.usage.input_tokens", it.toLong())
        }
        // For delete responses, set tracy.response.deleted from top-level "deleted" field
        body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.response.deleted", it)
        }
        body["store"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.response.store", it)
        }
        body["background"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.response.background", it)
        }
        // For delete responses, expose gen_ai.response.id from "id" field
        body["id"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.id", it)
        }
        body["service_tier"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("openai.response.service_tier", it)
        }
        body["status"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.status", it)
        }
        body["created_at"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.created_at", it)
        }
        body["completed_at"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("tracy.response.completed_at", it)
        }

        // For input_items list responses: extract first data item's id and type
        (body["data"] as? JsonArray)?.let { data ->
            span.setAttribute("tracy.response.list.count", data.size.toLong())
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.has_more", it) }
            data.firstOrNull()?.let { it as? JsonObject }?.let { firstItem ->
                firstItem["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.data.id", it) }
                firstItem["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.data.type", it) }
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
                    }
                }
            }
        }

        (body["usage"] as? JsonObject)?.let { usage ->
            setUsageAttributes(span, usage)
        }

        (body["usage"] as? JsonObject)?.get("output_tokens_details")?.let { (it as? JsonObject) }?.let { details ->
            details["reasoning_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute("tracy.response.usage.output_tokens_details.reasoning_tokens", it.toLong())
            }
        }

        (body["reasoning"] as? JsonObject)?.let { reasoning ->
            reasoning["effort"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.reasoning.effort", it) }
            reasoning["summary"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.reasoning.summary", it) }
        }

        ((body["text"] as? JsonObject)?.get("format") as? JsonObject)?.let { fmt ->
            fmt["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.text.format.type", it) }
        }

        // Set top-level response output attributes from first non-message output
        (body["output"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject) }?.let { firstOutput ->
            val outputType = firstOutput["type"]?.jsonPrimitive?.contentOrNull
            if (outputType != null && outputType != "message") {
                span.setAttribute("tracy.response.output.type", outputType)
                firstOutput["name"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.output.name", it) }
                firstOutput["call_id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.output.call_id", it) }
            }
        }

        span.populateUnmappedAttributes(body, mappedAttributes, PayloadType.RESPONSE)
    }

    override fun handleStreaming(span: Span, events: String): Unit = runCatching {
        for (line in events.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()

            val event = runCatching {
                Json.parseToJsonElement(data).jsonObject
            }.getOrNull() ?: continue

            when (event["type"]?.jsonPrimitive?.contentOrNull) {
                "response.created", "response.completed" -> {
                    // response object is nested under "response" key
                    val response = (event["response"] as? JsonObject) ?: continue
                    response["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.id", it) }
                    response["model"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.model", it) }
                    response["status"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.status", it) }
                    response["object"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.object", it) }
                    response["created_at"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.created_at", it) }
                    response["completed_at"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.completed_at", it) }
                    (response["usage"] as? JsonObject)?.let { usage ->
                        usage["input_tokens"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("gen_ai.usage.input_tokens", it.toLong()) }
                        usage["output_tokens"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("gen_ai.usage.output_tokens", it.toLong()) }
                    }
                }
                "response.output_text.done" -> {
                    event["text"]?.jsonPrimitive?.content?.let {
                        span.setAttribute("gen_ai.completion.0.content", it.orRedactedOutput())
                        span.setAttribute("gen_ai.completion.0.finish_reason", "stop")
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
                "message", null -> {  // null handles EasyInputMessage format (no explicit type field)
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
                            // Extract type from first non-input_text content item (e.g. input_file, input_image)
                            val firstNonTextContent = content
                                .firstOrNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull != "input_text" }
                                ?.let { it as? JsonObject }
                                ?: content.firstOrNull()?.let { it as? JsonObject }
                            firstNonTextContent?.let { firstContent ->
                                val contentType = firstContent["type"]?.jsonPrimitive?.contentOrNull
                                contentType?.let { span.setAttribute("tracy.request.input.content.type", it) }
                                when (contentType) {
                                    "input_image" -> {
                                        firstContent["detail"]?.jsonPrimitive?.contentOrNull?.let {
                                            span.setAttribute("tracy.request.input.image.detail", it)
                                        }
                                    }
                                    "input_file" -> {
                                        firstContent["file_id"]?.jsonPrimitive?.contentOrNull?.let {
                                            span.setAttribute("tracy.request.input.file.id", it)
                                        }
                                        firstContent["filename"]?.jsonPrimitive?.contentOrNull?.let {
                                            span.setAttribute("tracy.request.input.file.filename", it)
                                        }
                                        (firstContent["file_url"] ?: firstContent["file_data"])?.jsonPrimitive?.contentOrNull?.let {
                                            if (it.isNotEmpty()) span.setAttribute("tracy.request.input.file.filename",
                                                it.substringAfterLast('/').substringAfterLast('\\').ifEmpty { it })
                                        }
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
        (usage["input_tokens_details"] as? JsonObject)?.get("cached_tokens")?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.usage.cache_read.input_tokens", it)
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
        "background",
        "top_p",
        "max_output_tokens",
        "truncation",
        "parallel_tool_calls",
        "stream",
        "response_format",
        "tool_choice",
        "reasoning",
        "text",
        "include",
        "input",
        "instructions",
        "tools",
        "service_tier",
    )

    // https://platform.openai.com/docs/api-reference/responses/object
    private val mappedResponseAttributes: List<String> = listOf(
        "id", "object", "model", "deleted", "input_tokens",
        "store", "background", "reasoning", "text",
        "service_tier", "status", "created_at", "completed_at",
        "data",
        "output", "usage",
    )

    private val mappedAttributes = mappedRequestAttributes + mappedResponseAttributes

    companion object {
        private fun extractResponseIdFromPath(pathSegments: List<String>): String? {
            val responsesIndex = pathSegments.indexOf("responses")
            return if (responsesIndex >= 0 && pathSegments.size > responsesIndex + 1) {
                pathSegments[responsesIndex + 1].takeIf { it.isNotBlank() && it != "input_items" }
            } else null
        }

        fun resolveResponsesOperationName(pathSegments: List<String>, method: String): String {
            return when {
                pathSegments.contains("input_token_count") -> "response.input_tokens.count"
                pathSegments.contains("input_tokens") -> "response.input_tokens.count"
                pathSegments.contains("input_items") -> "response.input_items.list"
                pathSegments.contains("cancel") -> "response.cancel"
                pathSegments.contains("compact") -> "response.compact"
                method == "DELETE" -> "response.delete"
                method == "GET" -> "response.retrieve"
                else -> "generate_content"
            }
        }
    }
}
