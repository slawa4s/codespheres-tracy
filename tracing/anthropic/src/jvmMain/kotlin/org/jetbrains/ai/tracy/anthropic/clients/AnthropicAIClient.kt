/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.clients

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.AnthropicException
import com.anthropic.services.blocking.messages.BatchService
import io.opentelemetry.api.trace.StatusCode
import mu.KotlinLogging
import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.OpenTelemetryOkHttpInterceptor
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.patchOpenAICompatibleClient
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Instruments an Anthropic Claude client with OpenTelemetry tracing capabilities **inplace**.
 *
 * This function enables automatic tracing for all Anthropic API calls made through the provided client,
 * including message creation, streaming, tool calling, and multi-turn conversations. Trace data is
 * captured according to OpenTelemetry semantic conventions and can be exported to configured backends
 * (e.g., Langfuse, Jaeger, console).
 *
 * ## Use Cases
 *
 * ### Basic Message Creation
 * ```kotlin
 * val client = instrument(createAnthropicClient())
 * val params = MessageCreateParams.builder()
 *     .addUserMessage("Generate polite greeting and introduce yourself")
 *     .maxTokens(1000L)
 *     .temperature(0.8)
 *     .model(Model.CLAUDE_3_5_HAIKU_LATEST)
 *     .build()
 * client.messages().create(params)
 * // Request and response are automatically traced
 * ```
 *
 * ### Tool Calling
 * ```kotlin
 * val client = instrument(createAnthropicClient())
 * val toolName = "hi"
 * val greetTool = createTool(toolName)
 *
 * val params = MessageCreateParams.builder()
 *     .addUserMessage("Call the `hi` tool with the argument `name` set to 'USER'")
 *     .addTool(greetTool)
 *     .maxTokens(1000L)
 *     .temperature(0.0)
 *     .model(Model.CLAUDE_3_5_HAIKU_LATEST)
 *     .build()
 *
 * val response = client.messages().create(params)
 * // Tool definitions and tool call requests are automatically traced
 * ```
 *
 * ### Responding to Tool Calls
 * ```kotlin
 * val client = instrument(createAnthropicClient())
 * val paramsBuilder = MessageCreateParams.builder()
 *     .addUserMessage("Call the `hi` tool with the argument `name` set to 'USER'")
 *     .addTool(greetTool)
 *     .maxTokens(1000L)
 *     .model(Model.CLAUDE_3_5_HAIKU_LATEST)
 *
 * // First request - AI requests tool execution
 * val assistantMessage = client.messages().create(paramsBuilder.build())
 * paramsBuilder.addMessage(assistantMessage)
 *
 * // Add tool results
 * assistantMessage.content().forEach { block ->
 *     if (block.isToolUse()) {
 *         val toolUse = block.toolUse().get()
 *         paramsBuilder.addMessage(
 *             MessageParam.builder()
 *                 .contentOfBlockParams(listOf(
 *                     ContentBlockParam.ofToolResult(
 *                         ToolResultBlockParam.builder()
 *                             .toolUseId(toolUse.id())
 *                             .content("Hello, my dear friend!")
 *                             .build()
 *                     )
 *                 ))
 *                 .role(MessageParam.Role.USER)
 *                 .build()
 *         )
 *     }
 * }
 *
 * // Second request - AI processes tool results
 * client.messages().create(paramsBuilder.build())
 * // Tool results are traced in the conversation history
 * ```
 *
 * ### Streaming Responses
 * ```kotlin
 * val client = instrument(createAnthropicClient())
 * val params = MessageCreateParams.builder()
 *     .addUserMessage("Call the `hi` tool with the argument `name` set to 'USER'")
 *     .addTool(greetTool)
 *     .maxTokens(1000L)
 *     .model(Model.CLAUDE_3_5_HAIKU_LATEST)
 *     .build()
 *
 * val messageAccumulator = MessageAccumulator.create()
 * client.messages().createStreaming(params).use {
 *     it.stream().forEach(messageAccumulator::accumulate)
 * }
 * val message = messageAccumulator.message()
 * // Streaming events are automatically captured and traced
 * ```
 *
 * ## Notes
 * - This function is **idempotent**: calling `instrument()` multiple times on the same client
 *   will not result in duplicate tracing.
 * - Tracing can be controlled globally via `TracingManager.isTracingEnabled`.
 * - Content capture policies can be configured via `TracingManager.withCapturingPolicy(policy)`
 *   to redact sensitive input/output data.
 * - Error responses (e.g., 529 overload errors) are automatically captured with error status and messages.
 * - Multi-turn conversations with tool results are fully traced, showing the complete dialogue history.
 *
 * @param client The Anthropic client to instrument
 *
 * @see AnthropicLLMTracingAdapter
 * @see TracingManager
 * @see TracingManager.traceSensitiveContent
 */
fun instrument(client: AnthropicClient) {
    patchOpenAICompatibleClient(
        client = client,
        interceptor = OpenTelemetryOkHttpInterceptor(adapter = AnthropicLLMTracingAdapter())
    )
    patchBatchServiceWithValidationProxy(client)
}

/**
 * Installs a dynamic proxy around the [BatchService] returned by [AnthropicClient.messages().batches()].
 *
 * The Anthropic Java SDK validates parameters client-side (e.g. it rejects an empty `requests` array
 * before making any HTTP call). When such a validation exception is thrown the OkHttp interceptor
 * wired in by [patchOpenAICompatibleClient] never fires, so no span is created.
 *
 * This proxy catches those pre-HTTP exceptions and emits a completed error span carrying:
 * `gen_ai.provider.name`, `anthropic.api.type`, `server.address`, `server.port`, and `error.type`.
 *
 * Exceptions that are [AnthropicException] subclasses (HTTP-level errors) pass through without
 * creating a span here because the OkHttp interceptor already created one.
 */
private fun patchBatchServiceWithValidationProxy(client: AnthropicClient) {
    try {
        val baseUrl = reflectField(client, "clientOptions")
            .let { opts -> reflectField(opts, "baseUrl") as String }

        val uri = URI(baseUrl)
        val host = uri.host ?: "api.anthropic.com"
        val port = when {
            uri.port != -1 -> uri.port.toLong()
            uri.scheme == "https" -> 443L
            else -> 80L
        }

        val messageService = client.messages()
        val realBatchService = messageService.batches()

        // Guard: do not double-wrap if already proxied
        if (Proxy.isProxyClass(realBatchService.javaClass) &&
            Proxy.getInvocationHandler(realBatchService) is BatchServiceValidationTracingHandler
        ) {
            return
        }

        val proxy = Proxy.newProxyInstance(
            BatchService::class.java.classLoader,
            arrayOf(BatchService::class.java),
            BatchServiceValidationTracingHandler(realBatchService, host, port)
        ) as BatchService

        // Replace the batches$delegate Lazy in MessageServiceImpl so that all future calls
        // to messageService.batches() return our proxy instead of the real service.
        setReflectField(messageService, "batches\$delegate", lazyOf(proxy))
    } catch (e: Exception) {
        logger.warn(e) {
            "Tracy: failed to install batch validation proxy; SDK validation errors may not be traced for this client."
        }
    }
}

/**
 * Reads a field value from [instance] by traversing the class hierarchy.
 */
private fun reflectField(instance: Any, fieldName: String): Any {
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        try {
            val field = cls.getDeclaredField(fieldName)
            field.isAccessible = true
            return field.get(instance) ?: error("Field '$fieldName' is null on ${instance.javaClass.name}")
        } catch (_: NoSuchFieldException) {
            cls = cls.superclass
        }
    }
    error("Field '$fieldName' not found on ${instance.javaClass.name}")
}

/**
 * Sets a field value on [instance] by traversing the class hierarchy.
 */
private fun setReflectField(instance: Any, fieldName: String, value: Any?) {
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        try {
            val field = cls.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(instance, value)
            return
        } catch (_: NoSuchFieldException) {
            cls = cls.superclass
        }
    }
    error("Field '$fieldName' not found on ${instance.javaClass.name}")
}

/**
 * [InvocationHandler] that wraps a [BatchService] delegate and creates an OpenTelemetry error span
 * when the delegate throws an exception that is **not** an [AnthropicException].
 *
 * [AnthropicException] indicates that an HTTP call was made (the OkHttp interceptor already
 * handled span creation for those cases).  Any other exception (typically [IllegalStateException]
 * from SDK client-side validation) means no HTTP call was issued and no span would otherwise exist.
 */
private class BatchServiceValidationTracingHandler(
    private val delegate: BatchService,
    private val serverAddress: String,
    private val serverPort: Long,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        if (!TracingManager.isTracingEnabled) {
            return invokeDelegate(method, args)
        }

        return try {
            invokeDelegate(method, args)
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: throw e

            // AnthropicException = HTTP call was made → OkHttp interceptor owns the span
            if (cause is AnthropicException) throw cause

            // SDK threw before any HTTP call (e.g. empty `requests` validation) → emit error span
            val span = TracingManager.tracer.spanBuilder("Anthropic-generation").startSpan()
            try {
                span.setAttribute("gen_ai.provider.name", "anthropic")
                span.setAttribute("anthropic.api.type", "batches")
                span.setAttribute("server.address", serverAddress)
                span.setAttribute("server.port", serverPort)
                span.setAttribute("error.type", cause.javaClass.name)
                span.setStatus(StatusCode.ERROR)
                span.recordException(cause)
            } finally {
                span.end()
            }

            throw cause
        }
    }

    private fun invokeDelegate(method: Method, args: Array<Any?>?): Any? =
        if (args == null) method.invoke(delegate) else method.invoke(delegate, *args)
}
