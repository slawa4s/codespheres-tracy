/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.clients

import com.anthropic.client.AnthropicClient
import mu.KotlinLogging
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.OpenTelemetryOkHttpInterceptor
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.patchInterceptors
import org.jetbrains.ai.tracy.core.patchOpenAICompatibleClient

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
 * - The full client object graph is scanned for `OkHttpClient` instances, ensuring that all
 *   service implementations share the patched interceptor. `BatchServiceImpl` is lazily
 *   initialised by the SDK; `instrument()` calls `client.batches()` to force that
 *   `instrument()` calls `client.messages().batches()` to force that initialisation
 *   before the scan so that its `OkHttpClient` is always reachable.
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
    val interceptor = OpenTelemetryOkHttpInterceptor(adapter = AnthropicLLMTracingAdapter())
    patchOpenAICompatibleClient(client = client, interceptor = interceptor)
    // Scan the full object graph so all service impls (including BatchServiceImpl) are covered,
    // regardless of whether .batches() returns a factory-created instance per call.
    tryPatchAllOkHttpClients(client, interceptor)
    // Force lazy-init of BatchServiceImpl before scanning it: a null field at instrument-time is
    // skipped by tryPatchAllOkHttpClients, so calling .messages().batches() ensures the service is
    // instantiated and its OkHttpClient is reachable.
    runCatching { tryPatchAllOkHttpClients(client.messages().batches(), interceptor) }
}

/**
 * Patches [okHttpClient]'s interceptor list in-place by inserting [interceptor] (idempotent).
 */
private fun patchOkHttpClient(okHttpClient: OkHttpClient, interceptor: Interceptor) {
    val updated = patchInterceptors(okHttpClient.interceptors, interceptor)
    var targetCls: Class<*>? = okHttpClient.javaClass
    while (targetCls != null) {
        try {
            val interceptorsField = targetCls.getDeclaredField("interceptors")
            interceptorsField.isAccessible = true
            interceptorsField.set(okHttpClient, updated)
            break
        } catch (_: NoSuchFieldException) {
            targetCls = targetCls.superclass
        }
    }
}

/**
 * Recursively walks [obj]'s object graph (depth ≤ 5, identity-hash visited-set to break cycles),
 * scanning declared fields at each level. Fields assignable to [OkHttpClient] are patched with
 * [interceptor]; `java.util.Optional` and `java.util.concurrent.atomic.AtomicReference` fields
 * are transparently unwrapped and recursed at the same depth so that an `OkHttpClient` hidden
 * inside a JDK wrapper is still reachable; other non-primitive, non-array, non-JDK/Kotlin fields
 * are recursed into at `depth + 1`.
 * Used as a fallback when [patchOpenAICompatibleClient] fails for `BatchServiceImpl` due to SDK
 * version differences where the [OkHttpClient] is nested inside a holder (e.g. `ClientOptions`).
 */
internal fun tryPatchAllOkHttpClients(
    obj: Any,
    interceptor: Interceptor,
    depth: Int = 0,
    visited: MutableSet<Int> = mutableSetOf(),
) {
    if (OkHttpClient::class.java.isAssignableFrom(obj.javaClass)) {
        patchOkHttpClient(obj as OkHttpClient, interceptor)
        return
    }
    if (!visited.add(System.identityHashCode(obj))) return
    var cls: Class<*>? = obj.javaClass
    while (cls != null) {
        for (field in cls.declaredFields) {
            if (OkHttpClient::class.java.isAssignableFrom(field.type)) {
                try {
                    field.isAccessible = true
                    val okHttpClient = field.get(obj) as? OkHttpClient ?: continue
                    patchOkHttpClient(okHttpClient, interceptor)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to patch OkHttpClient field '${field.name}' in ${cls?.name}" }
                }
            } else if (java.util.Optional::class.java.isAssignableFrom(field.type)) {
                try {
                    field.isAccessible = true
                    val unwrapped = (field.get(obj) as? java.util.Optional<*>)?.orElse(null) ?: continue
                    tryPatchAllOkHttpClients(unwrapped, interceptor, depth, visited)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to unwrap Optional field '${field.name}' in ${cls?.name}" }
                }
            } else if (java.util.concurrent.atomic.AtomicReference::class.java.isAssignableFrom(field.type)) {
                try {
                    field.isAccessible = true
                    val unwrapped = (field.get(obj) as? java.util.concurrent.atomic.AtomicReference<*>)?.get() ?: continue
                    tryPatchAllOkHttpClients(unwrapped, interceptor, depth, visited)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to unwrap AtomicReference field '${field.name}' in ${cls?.name}" }
                }
            } else if (depth < 5 && !field.type.isPrimitive && !field.type.isArray &&
                !field.type.name.startsWith("java.") && !field.type.name.startsWith("kotlin.")
            ) {
                try {
                    field.isAccessible = true
                    val nested = field.get(obj) ?: continue
                    tryPatchAllOkHttpClients(nested, interceptor, depth + 1, visited)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to recurse into field '${field.name}' in ${cls?.name}" }
                }
            }
        }
        cls = cls.superclass
    }
}
