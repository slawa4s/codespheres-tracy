/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.populateUnmappedAttributes
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContent
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentPart
import org.jetbrains.ai.tracy.core.adapters.media.Resource
import org.jetbrains.ai.tracy.core.adapters.media.isValidUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.ContentKind
import org.jetbrains.ai.tracy.core.policy.contentTracingAllowed
import org.jetbrains.ai.tracy.core.policy.orRedacted
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MAX_TOKENS
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_FINISH_REASONS
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull


/**
 * Handler for OpenAI Chat Completions API
 */
internal class ChatCompletionsOpenAIApiEndpointHandler(
    private val extractor: MediaContentExtractor
) : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val operationName = when {
            request.method == "DELETE" -> "chat.completions.delete"
            request.method == "PATCH" -> "chat.completions.update"
            request.method == "GET" && request.url.pathSegments.contains("messages") -> "chat.completions.messages.list"
            request.method == "GET" && request.url.pathSegments.any { it.matches(Regex("[a-zA-Z0-9_-]{10,}")) } -> "chat.completions.retrieve"
            request.method == "GET" -> "chat.completions.list"
            else -> "chat"
        }
        span.setAttribute("gen_ai.operation.name", operationName)
        span.setAttribute("openai.api.type", "chat_completions")

        // Handle list/retrieve/delete GET query params
        if (request.method == "GET" || request.method == "DELETE") {
            val params = request.url.parameters
            params.queryParameter("limit")?.toLongOrNull()?.let { span.setAttribute("tracy.request.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
            params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
            // For messages.list: extract completion_id from path
            if (operationName == "chat.completions.messages.list") {
                val segments = request.url.pathSegments
                val completionsIdx = segments.indexOf("completions")
                if (completionsIdx >= 0 && segments.size > completionsIdx + 1) {
                    span.setAttribute("tracy.request.completion_id", segments[completionsIdx + 1])
                }
            }
        }

        val body = request.body.asJson()?.jsonObject ?: return

        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        body["stream"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("gen_ai.request.stream", it)
        }

        body["messages"]?.let {
            for ((index, message) in it.jsonArray.withIndex()) {
                val role = message.jsonObject["role"]?.jsonPrimitive?.content
                val kind = kindByRole(role)

                span.setAttribute("gen_ai.prompt.$index.role", role)

                // content may be of different schemas
                val messageContent = message.jsonObject["content"]
                attachRequestContent(span, index, kind, messageContent)

                // when a tool result is encountered
                if (role?.lowercase() == "tool") {
                    span.setAttribute(
                        "gen_ai.prompt.$index.tool_call_id",
                        message.jsonObject["tool_call_id"]?.jsonPrimitive?.content
                    )
                }
            }
        }

        // See: https://platform.openai.com/docs/api-reference/chat/create
        body["tools"]?.let { tools ->
            if (tools is JsonArray) {
                span.setAttribute("gen_ai.tool.definitions", tools.toString())
                for ((index, tool) in tools.jsonArray.withIndex()) {
                    val toolType = tool.jsonObject["type"]?.jsonPrimitive?.content
                    span.setAttribute("gen_ai.tool.$index.type", toolType)

                    tool.jsonObject["function"]?.jsonObject?.let {
                        val toolName = it["name"]?.jsonPrimitive?.content
                        val toolDescription = it["description"]?.jsonPrimitive?.content
                        val toolParameters = it["parameters"]?.jsonObject?.toString()
                        val strict = it["strict"]?.jsonPrimitive?.boolean?.toString()

                        span.setAttribute("gen_ai.tool.$index.name", toolName?.orRedactedInput())
                        span.setAttribute("gen_ai.tool.$index.description", toolDescription?.orRedactedInput())
                        span.setAttribute("gen_ai.tool.$index.parameters", toolParameters?.orRedactedInput())
                        span.setAttribute("gen_ai.tool.$index.strict", strict)

                        if (index == 0) {
                            toolName?.let { name -> span.setAttribute("gen_ai.tool.name", name.orRedactedInput()) }
                        }
                    }
                }
            }
        }

        // tool_choice: string primitive or object with function.name
        body["tool_choice"]?.let { toolChoice ->
            val toolChoiceValue = when (toolChoice) {
                is JsonPrimitive -> toolChoice.content
                is JsonObject -> toolChoice["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                else -> null
            }
            toolChoiceValue?.let { span.setAttribute("tracy.request.tool_choice", it) }
        }

        (body["max_tokens"] ?: body["max_completion_tokens"])?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute(GEN_AI_REQUEST_MAX_TOKENS, it)
        }

        body["logprobs"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.request.logprobs", it) }

        body["top_logprobs"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.request.top_logprobs", it.toLong()) }

        body["store"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.request.store", it) }

        (body["messages"] as? JsonArray)?.size?.let { span.setAttribute("tracy.request.messages.count", it.toLong()) }

        (body["messages"] as? JsonArray)?.count { (it as? JsonObject)?.get("role")?.jsonPrimitive?.content == "system" }?.let {
            if (it > 0) span.setAttribute("tracy.request.system_messages.count", it.toLong())
        }

        (body["metadata"] as? JsonObject)?.size?.let { if (it > 0) span.setAttribute("tracy.request.metadata.count", it.toLong()) }

        span.populateUnmappedAttributes(body, mappedAttributes, PayloadType.REQUEST)
    }

    /**
     * Inserts the message content depending on its type.
     *
     * The content can be either a normal text (i.e., a string) or
     * an array when a media input is attached (e.g., images, audio, and files).
     *
     * For more details on possible content structures,
     * see [User Message Content Description](https://platform.openai.com/docs/api-reference/chat/create#chat-create-messages-user-message-content).
     *
     * Additionally, see: [OpenAI Chat Completions](https://platform.openai.com/docs/api-reference/chat/create#chat-create-messages)
     */
    private fun attachRequestContent(
        span: Span,
        index: Int,
        kind: ContentKind,
        content: JsonElement?,
    ) {
        if (content == null) {
            span.setAttribute("gen_ai.prompt.$index.content", null)
            return
        }

        // See content types: https://platform.openai.com/docs/api-reference/chat/create#chat-create-messages-user-message-content
        val result: String = when (content) {
            is JsonPrimitive -> content.jsonPrimitive.content
            is JsonArray -> {
                // install upload media attributes only when tracing is allowed
                if (contentTracingAllowed(kind)) {
                    // array that contains entries of either image, audio, file or normal text
                    val mediaContent = parseMediaContent(content)
                    extractor.setUploadableContentAttributes(span, field = "input", mediaContent)
                }
                content.jsonArray.toString()
            }

            else -> content.toString()
        }
        span.setAttribute("gen_ai.prompt.$index.content", result.orRedacted(kind))
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["choices"]?.let { choices ->
            for ((index, choice) in choices.jsonArray.withIndex()) {
                val index = choice.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: index

                span.setAttribute(
                    "gen_ai.completion.$index.finish_reason",
                    choice.jsonObject["finish_reason"]?.jsonPrimitive?.content
                )

                choice.jsonObject["message"]?.jsonObject?.let { message ->
                    val role = message.jsonObject["role"]?.jsonPrimitive?.content
                    val content = message.jsonObject["content"]?.toString()

                    span.setAttribute("gen_ai.completion.$index.role", role)
                    span.setAttribute("gen_ai.completion.$index.content", content?.orRedactedOutput())

                    // See: https://platform.openai.com/docs/api-reference/chat/object
                    message.jsonObject["tool_calls"]?.let { toolCalls ->
                        // sometimes, this prop is explicitly set to null, hence, being JsonNull.
                        // therefore, we check for the required array type
                        if (toolCalls is JsonArray) {
                            span.setAttribute("tracy.response.tool_call.count", toolCalls.size.toLong())
                            for ((toolCallIndex, toolCall) in toolCalls.jsonArray.withIndex()) {
                                val callId = toolCall.jsonObject["id"]?.jsonPrimitive?.content
                                span.setAttribute(
                                    "gen_ai.completion.$index.tool.$toolCallIndex.call.id",
                                    callId
                                )
                                span.setAttribute(
                                    "gen_ai.completion.$index.tool.$toolCallIndex.call.type",
                                    toolCall.jsonObject["type"]?.jsonPrimitive?.content
                                )
                                if (toolCallIndex == 0) {
                                    callId?.let { span.setAttribute("gen_ai.tool.call.id", it) }
                                }

                                toolCall.jsonObject["function"]?.jsonObject?.let {
                                    val name = it["name"]?.jsonPrimitive?.content
                                    val arguments = it["arguments"]?.jsonPrimitive?.content

                                    span.setAttribute(
                                        "gen_ai.completion.$index.tool.$toolCallIndex.name",
                                        name?.orRedactedOutput()
                                    )
                                    span.setAttribute(
                                        "gen_ai.completion.$index.tool.$toolCallIndex.arguments",
                                        arguments?.orRedactedOutput()
                                    )
                                    if (toolCallIndex == 0) {
                                        arguments?.let { args -> span.setAttribute("gen_ai.tool.call.arguments", args.orRedactedOutput()) }
                                    }
                                }
                            }
                        }
                    }

                    span.setAttribute(
                        "gen_ai.completion.$index.annotations",
                        message.jsonObject["annotations"].toString()
                    )
                }
            }
        }

        (body["usage"] as? JsonObject)?.let { usage ->
            setUsageAttributes(span, usage)
        }

        // logprobs token count from first choice
        ((body["choices"] as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }?.get("logprobs") as? JsonObject)
            ?.get("content")?.let { it as? JsonArray }?.size?.let {
                span.setAttribute("tracy.response.logprobs.token.count", it.toLong())
            }

        val finishReasons = (body["choices"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("finish_reason")?.jsonPrimitive?.contentOrNull }
            ?: emptyList()
        if (finishReasons.isNotEmpty()) {
            span.setAttribute(GEN_AI_RESPONSE_FINISH_REASONS, finishReasons)
        }

        body["service_tier"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("openai.response.service_tier", it)
        }
        body["system_fingerprint"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("openai.response.system_fingerprint", it)
        }

        // Delete response: record deleted flag and response id
        body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.deleted", it) }
        body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.id", it) }

        // Messages list response (chat.completions.messages.list)
        val dataArray = body["data"] as? JsonArray
        if (dataArray != null) {
            val firstObject = dataArray.firstOrNull()?.let { it as? JsonObject }
            if (firstObject != null && firstObject.containsKey("role")) {
                // data contains message objects
                dataArray.size.let { span.setAttribute("tracy.chat.completion.messages.count", it.toLong()) }
            } else {
                // data contains chat completion objects (list response)
                dataArray.size.let { span.setAttribute("tracy.chat.completions.count", it.toLong()) }
            }
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.has_more", it) }
        }

        span.populateUnmappedAttributes(body, mappedAttributes, PayloadType.RESPONSE)
    }

    override fun handleStreaming(span: Span, events: String): Unit = runCatching {
        var role: String? = null
        var finishReason: String? = null
        var responseId: String? = null
        var responseModel: String? = null
        val out = buildString {
            for (line in events.lineSequence()) {
                if (!line.startsWith("data:")) {
                    continue
                }
                val data = line.removePrefix("data:").trim()

                val event = runCatching {
                    Json.parseToJsonElement(data).jsonObject
                }.getOrNull() ?: continue

                if (responseId == null) {
                    responseId = event["id"]?.jsonPrimitive?.contentOrNull
                }
                if (responseModel == null) {
                    responseModel = event["model"]?.jsonPrimitive?.contentOrNull
                }

                val choice = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"]?.jsonObject ?: continue

                if (role == null) {
                    role = delta["role"]?.jsonPrimitive?.content
                }
                delta["content"]?.jsonPrimitive?.content?.let { append(it) }
                choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
            }
        }

        if (out.isNotEmpty()) {
            val kind = kindByRole(role)
            span.setAttribute("gen_ai.completion.0.content", out.orRedacted(kind))
        }
        role?.let { span.setAttribute("gen_ai.completion.0.role", it) }
        finishReason?.let { span.setAttribute(GEN_AI_RESPONSE_FINISH_REASONS, listOf(it)) }
        responseId?.let { span.setAttribute("gen_ai.response.id", it) }
        responseModel?.let { span.setAttribute("gen_ai.response.model", it) }

        return@runCatching
    }.getOrElse { exception ->
        span.setStatus(StatusCode.ERROR)
        span.recordException(exception)
    }

    /**
     * Sets usage attributes (prompt_tokens/completion_tokens)
     */
    private fun setUsageAttributes(span: Span, usage: JsonObject) {
        usage["prompt_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
        }
        usage["completion_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, it)
        }
    }

    /**
     * Given a role, define what content kind matches it, either input or output.
     */
    private fun kindByRole(role: String?): ContentKind = when (role) {
        // role may be:
        //   1. input: developer/system/user
        "developer", "system", "user" -> ContentKind.INPUT
        //   2. output: assistant/tool/function
        else -> ContentKind.OUTPUT
    }

    /**
     * Extracts media content parts (images, audio, files) from JSON content.
     *
     * As for files, supports only files attached directly in the data URL (i.e., in the `file_data` field).
     * Files attached via file IDs (`file_id` field) are ignored.
     * See the schema for files: [Chat Completions API: File Content Schema](https://platform.openai.com/docs/api-reference/chat/create#chat-create-messages-user-message-content-array-of-content-parts-file-content-part-file).
     *
     * See endpoint details: [Chat Completions API](https://platform.openai.com/docs/api-reference/chat/create)
     */
    private fun parseMediaContent(content: JsonArray): MediaContent {
        val parts = buildList {
            for (part in content) {
                val type = part.jsonObject["type"]?.jsonPrimitive?.content ?: continue

                val mediaPart = when (type) {
                    "image_url" -> {
                        val url = part.jsonObject["image_url"]?.jsonObject["url"]?.jsonPrimitive?.content ?: continue

                        if (url.isValidUrl()) {
                            MediaContentPart(resource = Resource.Url(url))
                        } else if (url.startsWith("data:")) {
                            MediaContentPart(resource = Resource.InlineDataUrl(url))
                        } else {
                            null
                        }
                    }

                    "input_audio" -> {
                        // data is base64-encoded
                        val data = part.jsonObject["input_audio"]?.jsonObject["data"]?.jsonPrimitive?.content
                            ?: continue
                        val format = part.jsonObject["input_audio"]?.jsonObject["format"]?.jsonPrimitive?.content
                            ?: continue

                        MediaContentPart(resource = Resource.Base64(data, mediaType = "audio/$format"))
                    }

                    "file" -> {
                        // OpenAI expects a data url with a base64-encoded PDF file
                        val fileData = part.jsonObject["file"]?.jsonObject["file_data"]?.jsonPrimitive?.content
                            ?: continue
                        MediaContentPart(resource = Resource.InlineDataUrl(fileData))
                    }

                    else -> null
                }

                // append media part if it's valid
                if (mediaPart != null) {
                    add(mediaPart)
                }
            }
        }

        return MediaContent(parts)
    }

    // https://platform.openai.com/docs/api-reference/chat/create
    private val mappedRequestAttributes: List<String> = listOf(
        "messages",
        "model",
        "tools",
        "tool_choice",
        "choices",
        "temperature"
    )

    // https://platform.openai.com/docs/api-reference/chat/object
    private val mappedResponseAttributes: List<String> = listOf(
        "choices",
        "usage"
    )

    private val mappedAttributes = mappedRequestAttributes + mappedResponseAttributes
}
