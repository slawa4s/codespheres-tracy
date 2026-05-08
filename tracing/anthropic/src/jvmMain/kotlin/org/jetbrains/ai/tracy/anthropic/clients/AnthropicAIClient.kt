/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.clients

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.AnthropicException
import com.anthropic.services.blocking.messages.BatchService
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiSystemIncubatingValues
import mu.KotlinLogging
import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.OpenTelemetryOkHttpInterceptor
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.patchOpenAICompatibleClient
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Marker interface used to detect that a [BatchService] has already been wrapped by Tracy's
 * pre-HTTP error tracing proxy. Prevents double-instrumentation when [instrument] is called
 * more than once on the same client.
 */
private interface TracyBatchServiceProxy

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
 * - Pre-HTTP failures in `batches().create()` (e.g., SDK validation errors thrown before any HTTP call
 *   is made) are captured via a dynamic proxy and emitted as error spans even when no HTTP call occurs.
 *
 * @param client The Anthropic client to instrument
 *
 * @see AnthropicLLMTracingAdapter
 * @see TracingManager
 * @see TracingManager.traceSensitiveContent
 */
fun instrument(client: AnthropicClient) {
    try {
        patchOpenAICompatibleClient(
            client = client,
            interceptor = OpenTelemetryOkHttpInterceptor(adapter = AnthropicLLMTracingAdapter())
        )
    } catch (e: Exception) {
        logger.warn(e) {
            "Failed to instrument AnthropicClient via reflection — no tracing spans will be emitted. " +
            "This typically indicates an incompatible Anthropic SDK version. " +
            "Cause: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    instrumentBatchesService(client)
}

/**
 * Wraps the [BatchService] obtained from [client] with a dynamic proxy that emits an error
 * span whenever [BatchService.create] throws an exception that was **not** caused by an HTTP
 * response (i.e., the SDK threw before any HTTP call was attempted).
 *
 * This complements the OkHttp-level [OpenTelemetryOkHttpInterceptor]: that interceptor records
 * spans for all HTTP interactions, but it never fires when the SDK raises an exception prior to
 * making an HTTP call (for example, an `IllegalStateException` thrown by the SDK's parameter
 * validation before the request is sent). The proxy closes that gap by catching such
 * non-[AnthropicException] failures and recording a span with:
 * - `gen_ai.provider.name = "anthropic"`
 * - `anthropic.api.type = "batches"`
 * - `gen_ai.operation.name = "batches.create"`
 * - `error.type` (the exception class name)
 * - `server.address` / `server.port` (derived from the client's base URL)
 *
 * The instrumentation is **idempotent**: a second call on the same client is a no-op because
 * the proxy already wraps [BatchService].
 */
private fun instrumentBatchesService(client: AnthropicClient) {
    try {
        // Extract server address and port from the client's configured base URL.
        // SDK versions >= 2.x store the base URL in the Backend (not in ClientOptions.baseUrl),
        // so we try both sources and fall back to the Anthropic production URL when neither has it.
        val clientOptions = getBatchFieldInHierarchy(client, "clientOptions")
        val baseUrl = resolveBaseUrl(clientOptions) ?: return
        val uri = URI.create(baseUrl)
        val serverAddress = uri.host ?: return
        val serverPort = when {
            uri.port > 0 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }

        // Obtain the MessageService implementation; this also forces its lazy initialisation.
        val messagesService = client.messages()

        // Obtain the current BatchService, forcing its lazy initialisation inside MessageServiceImpl.
        val originalBatchService = messagesService.batches()

        // Idempotency guard: if the batch service is already one of our proxies, skip.
        if (originalBatchService is TracyBatchServiceProxy) return

        // Create the proxy that wraps the original BatchService.
        val batchProxy = createBatchServiceProxy(originalBatchService, serverAddress, serverPort)

        // Try to install the proxy via the Kotlin lazy-delegate field first; if that fails
        // (e.g., the SDK version uses a plain `val` instead of `by lazy`), fall back to a
        // type-based scan of all declared fields.
        if (!tryInjectViaDelegateField(messagesService, batchProxy) &&
            !tryInjectViaDirectField(messagesService, batchProxy)) {
            logger.warn {
                "Failed to wrap AnthropicClient batch service for pre-HTTP error tracing — " +
                "batch creation errors that occur before the HTTP call will not be traced. " +
                "Neither the lazy-delegate field 'batches\$delegate' nor any direct " +
                "BatchService-typed field was found in ${messagesService.javaClass.name}."
            }
        }
    } catch (e: Exception) {
        logger.warn(e) {
            "Failed to wrap AnthropicClient batch service for pre-HTTP error tracing — " +
            "batch creation errors that occur before the HTTP call will not be traced. " +
            "Cause: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}

/**
 * Creates a [java.lang.reflect.Proxy] for [BatchService] that wraps [original].
 *
 * All methods are forwarded to [original] unchanged, **except** for any overload of
 * `create(...)`: if `original.create(...)` throws a [Throwable] that is **not** an
 * [AnthropicException] (which would indicate an HTTP-layer error already handled by the
 * OkHttp interceptor), a dedicated error span is emitted before the exception is re-thrown.
 *
 * The proxy also implements [TracyBatchServiceProxy] so that repeated calls to
 * [instrumentBatchesService] are no-ops.
 */
private fun createBatchServiceProxy(
    original: BatchService,
    serverAddress: String,
    serverPort: Int,
): BatchService {
    val handler = java.lang.reflect.InvocationHandler { _, method, args ->
        val actualArgs: Array<Any?> = args ?: emptyArray()

        if (!TracingManager.isTracingEnabled || method.name != "create") {
            return@InvocationHandler method.invoke(original, *actualArgs)
        }

        // Intercept all create(...) overloads.
        try {
            method.invoke(original, *actualArgs)
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: e
            // AnthropicException means the SDK received an HTTP error response;
            // the OkHttp interceptor already created a span for that case.
            if (cause !is AnthropicException) {
                createPreHttpBatchErrorSpan(cause, serverAddress, serverPort)
            }
            throw cause
        }
    }

    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(
        original.javaClass.classLoader,
        arrayOf(BatchService::class.java, TracyBatchServiceProxy::class.java),
        handler,
    ) as BatchService
}

/**
 * Emits a completed error span that represents a `batches.create` call that failed
 * **before** any HTTP request was attempted (e.g., SDK-level parameter validation).
 */
private fun createPreHttpBatchErrorSpan(
    error: Throwable,
    serverAddress: String,
    serverPort: Int,
) {
    val span = TracingManager.tracer.spanBuilder("Anthropic-generation").startSpan()
    try {
        span.setAttribute("gen_ai.provider.name", GenAiSystemIncubatingValues.ANTHROPIC)
        span.setAttribute("anthropic.api.type", "batches")
        span.setAttribute(GEN_AI_OPERATION_NAME, "batches.create")
        span.setAttribute("error.type", error.javaClass.canonicalName ?: error.javaClass.name)
        span.setAttribute("server.address", serverAddress)
        span.setAttribute(AttributeKey.longKey("server.port"), serverPort.toLong())
        span.setStatus(StatusCode.ERROR)
        span.recordException(error)
    } finally {
        span.end()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Base URL resolution
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Resolves the base URL from [clientOptions] using a multi-step fallback strategy:
 *
 * 1. Tries `clientOptions.baseUrl()` directly (works in older SDK versions that store the URL
 *    in `ClientOptions` itself).
 * 2. If that returns `null`, traverses `clientOptions → [originalHttpClient | httpClient] → backend`
 *    via reflection and calls `backend.baseUrl()` (works in SDK versions that store the URL
 *    inside the HTTP-transport backend object instead).
 *
 * Returns `null` only if both paths fail, which is treated as "unable to determine server
 * coordinates" and causes [instrumentBatchesService] to skip proxy installation.
 */
private fun resolveBaseUrl(clientOptions: Any): String? {
    // Fast path: the URL is stored directly on ClientOptions (older SDK versions).
    try {
        val direct = clientOptions.javaClass.getMethod("baseUrl").invoke(clientOptions) as? String
        if (!direct.isNullOrEmpty()) return direct
    } catch (_: Exception) { /* fall through */ }

    // Slow path: the URL lives inside the Backend stored in the HTTP-transport client
    // (SDK versions >= 2.x where baseUrl is set via .backend(...) instead of .baseUrl(...)).
    try {
        val httpClient = try {
            getBatchFieldInHierarchy(clientOptions, "originalHttpClient")
        } catch (_: NoSuchFieldException) {
            getBatchFieldInHierarchy(clientOptions, "httpClient")
        }
        val backend = getBatchFieldInHierarchy(httpClient, "backend")
        val backendUrl = backend.javaClass.getMethod("baseUrl").invoke(backend) as? String
        if (!backendUrl.isNullOrEmpty()) return backendUrl
    } catch (_: Exception) { /* fall through */ }

    return null
}

// ─────────────────────────────────────────────────────────────────────────────
// Batch service injection helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Attempts to install [batchProxy] by swapping the cached value inside the Kotlin
 * lazy-delegate field `batches$delegate` of [messagesService].
 *
 * This covers the common case where the Anthropic SDK declares the `batches` property
 * as `private val batches: BatchService by lazy { … }`, which the Kotlin compiler
 * compiles to a field named `batches$delegate` of type `SynchronizedLazyImpl`.
 *
 * @return `true` if the proxy was successfully installed; `false` on any exception
 *         (e.g., `NoSuchFieldException` when the SDK version uses a different layout).
 */
internal fun tryInjectViaDelegateField(messagesService: Any, batchProxy: BatchService): Boolean {
    return try {
        val batchesDelegateField = getBatchDeclaredField(messagesService, "batches\$delegate")
        val lazyDelegate = batchesDelegateField.get(messagesService)
        // Ensure the Lazy has been initialised so `_value` holds a real object (not UNINITIALIZED_VALUE).
        (lazyDelegate as Lazy<*>).value
        val valueField = getBatchDeclaredField(lazyDelegate, "_value")
        valueField.set(lazyDelegate, batchProxy)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Attempts to install [batchProxy] by finding any declared field in the class hierarchy of
 * [messagesService] whose type is assignable to [BatchService] and setting it directly.
 *
 * This covers SDK versions that store the batch service in a plain `val` (not a `by lazy`
 * delegate), where the backing field is typed as [BatchService] (or a concrete subtype).
 *
 * @return `true` if a suitable field was found and set to [batchProxy]; `false` otherwise.
 */
internal fun tryInjectViaDirectField(messagesService: Any, batchProxy: BatchService): Boolean {
    return try {
        var cls: Class<*>? = messagesService.javaClass
        while (cls != null) {
            for (field in cls.declaredFields) {
                if (BatchService::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    field.set(messagesService, batchProxy)
                    return true
                }
            }
            cls = cls.superclass
        }
        false
    } catch (_: Exception) {
        false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reflection helpers (private, scoped to this file)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns the value of [fieldName] by traversing the class hierarchy of [instance],
 * making the field accessible along the way.
 */
private fun getBatchFieldInHierarchy(instance: Any, fieldName: String): Any {
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        try {
            val field = cls.getDeclaredField(fieldName)
            field.isAccessible = true
            return field.get(instance)
                ?: throw IllegalStateException("Field '$fieldName' is null in ${instance.javaClass.name}")
        } catch (_: NoSuchFieldException) {
            cls = cls.superclass
        }
    }
    throw NoSuchFieldException("Field '$fieldName' not found in ${instance.javaClass.name}")
}

/**
 * Returns a [java.lang.reflect.Field] named [fieldName] from the class hierarchy of [instance],
 * making it accessible so the caller can read or write its value.
 */
private fun getBatchDeclaredField(instance: Any, fieldName: String): java.lang.reflect.Field {
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        try {
            val field = cls.getDeclaredField(fieldName)
            field.isAccessible = true
            return field
        } catch (_: NoSuchFieldException) {
            cls = cls.superclass
        }
    }
    throw NoSuchFieldException("Field '$fieldName' not found in ${instance.javaClass.name}")
}
