/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.core.http.protocol.*
import okhttp3.Request as OkHttpRequest
import okhttp3.Response as OkHttpResponse
import okhttp3.ResponseBody as OkHttpResponseBody

private val logger = KotlinLogging.logger {}

/**
 * Instruments an [OkHttpClient] with OpenTelemetry tracing for LLM provider API calls,
 * returning a cloned and patched instance of the provided [OkHttpClient].
 *
 * This function adds automatic tracing capabilities to an OkHttp client by injecting an
 * [OpenTelemetryOkHttpInterceptor] configured with the provided [LLMTracingAdapter]. All HTTP
 * requests made through the instrumented client to LLM provider APIs will be automatically traced,
 * including request/response bodies, token usage, model parameters, tool calls, and errors.
 * Supports both streaming and non-streaming requests.
 *
 * This is a lower-level instrumentation function that works with raw OkHttp clients. For
 * provider-specific clients (OpenAI, Anthropic, Gemini), consider using the provider-specific
 * `instrument()` functions instead.
 *
 * ## Use Cases
 *
 * ### Basic OpenAI API Request
 * ```kotlin
 * TracingManager.setSdk(configureOpenTelemetrySdk(ConsoleExporterConfig()))
 * TracingManager.traceSensitiveContent()
 *
 * val apiToken = System.getenv("OPENAI_API_KEY")
 * val requestBodyJson = buildJsonObject {
 *     put("model", JsonPrimitive("gpt-4o-mini"))
 *     put("messages", buildJsonArray {
 *         add(buildJsonObject {
 *             put("role", JsonPrimitive("user"))
 *             put("content", JsonPrimitive("Generate polite greeting and introduce yourself"))
 *         })
 *     })
 *     put("temperature", JsonPrimitive(1.0))
 * }
 *
 * val client = OkHttpClient()
 * val instrumentedClient = instrument(client, OpenAILLMTracingAdapter())
 *
 * val requestBody = Json { prettyPrint = true }
 *     .encodeToString(requestBodyJson)
 *     .toRequestBody("application/json".toMediaType())
 *
 * val request = Request.Builder()
 *     .url("https://api.openai.com/v1/chat/completions")
 *     .addHeader("Authorization", "Bearer $apiToken")
 *     .addHeader("Content-Type", "application/json")
 *     .post(requestBody)
 *     .build()
 *
 * instrumentedClient.newCall(request).execute().use { response ->
 *     println("Result: ${response.body?.string()}")
 * }
 *
 * TracingManager.flushTraces()
 * ```
 *
 * ### Using with Different Providers
 * ```kotlin
 * // OpenAI
 * val openAiClient = instrument(OkHttpClient(), OpenAILLMTracingAdapter())
 *
 * // Anthropic
 * val anthropicClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())
 *
 * // Gemini
 * val geminiClient = instrument(OkHttpClient(), GeminiLLMTracingAdapter())
 * ```
 *
 * ### Streaming Requests
 * ```kotlin
 * val client = instrument(OkHttpClient(), OpenAILLMTracingAdapter())
 * val request = Request.Builder()
 *     .url("https://api.openai.com/v1/chat/completions")
 *     .addHeader("Authorization", "Bearer $apiToken")
 *     .post(streamingRequestBody)
 *     .build()
 *
 * client.newCall(request).execute().use { response ->
 *     response.body?.source()?.let { source ->
 *         // Read streaming response
 *         while (!source.exhausted()) {
 *             val line = source.readUtf8Line()
 *             // Process streaming data
 *         }
 *     }
 * }
 * // Streaming data is automatically captured and traced
 * ```
 *
 * ## Notes
 * - This function is **idempotent**: calling `instrument()` multiple times on the same client
 *   will not result in duplicate interceptors.
 * - Tracing can be controlled globally via `TracingManager.isTracingEnabled`.
 * - **The original client is not modified; a new client instance with instrumentation is returned**.
 * - Content capture policies [TracingManager.contentCapturePolicy] can be configured to redact sensitive data.
 * - Error responses are automatically captured with error status and messages.
 *
 * @param client The OkHttp client to instrument
 * @param adapter The [LLMTracingAdapter] specifying which LLM provider adapter to use for tracing
 * @return **A new [OkHttpClient] instance** with OpenTelemetry tracing enabled (i.e., the initial [client] remains **unmodified**)
 *
 * @see TracingManager
 * @see TracingManager.traceSensitiveContent
 */
fun instrument(client: OkHttpClient, adapter: LLMTracingAdapter): OkHttpClient {
    val clientBuilder = client.newBuilder()

    val interceptor = OpenTelemetryOkHttpInterceptor(adapter)
    patchInterceptorsInplace(clientBuilder.interceptors(), interceptor)

    return clientBuilder.build()
}

/**
 * Patches the OpenAI-compatible client by injecting a custom interceptor into its internal HTTP client
 * **in-place**.
 *
 * This method modifies the internal structure of the provided OpenAI-like client to replace its HTTP client interceptors
 * with the specified interceptor.
 * Supports OpenAI-compatible (**in terms of internal class structure**) clients.
 *
 * The reflection path traversed is:
 * ```
 * client.clientOptions
 *   → [originalHttpClient | httpClient]   (falls back to "httpClient" when "originalHttpClient" absent)
 *   → [if not OkHttpClient wrapper] .httpClient
 *   → .okHttpClient (okhttp3.OkHttpClient, resolved by name then by type as a fallback)
 * ```
 *
 * @param client The instance of the OpenAI-compatible client to patch.
 * @param interceptor The interceptor to be injected into the internal HTTP client of the OpenAI-compatible client.
 */
fun <T> patchOpenAICompatibleClient(client: T, interceptor: Interceptor) {
    val clientOptions = getFieldValue(client as Any, "clientOptions")

    // Some SDK versions store the unwrapped HTTP client under "originalHttpClient"; others may only
    // have "httpClient". Fall back to "httpClient" when the primary field name is absent so the
    // instrumentation stays compatible across minor SDK releases.
    val httpClientHolder = try {
        getFieldValue(clientOptions, "originalHttpClient")
    } catch (_: NoSuchFieldException) {
        getFieldValue(clientOptions, "httpClient")
    }

    val okHttpHolder = if (httpClientHolder::class.simpleName == "OkHttpClient") {
        httpClientHolder
    } else {
        try {
            getFieldValue(httpClientHolder, "httpClient")
        } catch (_: NoSuchFieldException) {
            // The holder itself may directly contain the okHttpClient field; use it as-is.
            httpClientHolder
        }
    }

    // Resolve the underlying okhttp3.OkHttpClient. Try by field name first; if that fails (e.g.
    // because of Kotlin @JvmSynthetic name mangling across SDK versions) fall back to a type-based
    // scan of all declared fields so the lookup remains version-resilient.
    val okHttpClient: OkHttpClient = try {
        getFieldValue(okHttpHolder, "okHttpClient") as OkHttpClient
    } catch (_: Exception) {
        findFieldByType(okHttpHolder, OkHttpClient::class.java)
            ?: throw NoSuchFieldException(
                "Could not find okhttp3.OkHttpClient field in ${okHttpHolder.javaClass.name}. " +
                "This may indicate an incompatible SDK version."
            )
    }

    // add a given interceptor if the current list of interceptors doesn't contain it already
    val updatedInterceptors = patchInterceptors(okHttpClient.interceptors, interceptor)
    setFieldValue(okHttpClient, "interceptors", updatedInterceptors)
}

internal fun getFieldValue(instance: Any, fieldName: String): Any {
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        try {
            val field = cls.getDeclaredField(fieldName)
            field.isAccessible = true
            return field.get(instance) ?: throw IllegalStateException("Field '$fieldName' is null")
        } catch (_: NoSuchFieldException) {
            cls = cls.superclass
        }
    }
    throw NoSuchFieldException("Field '$fieldName' not found in ${instance.javaClass.name}")
}

internal fun setFieldValue(instance: Any, fieldName: String, value: Any?) {
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        try {
            val field = cls.getDeclaredField(fieldName)
            field.isAccessible = true

            if (value == null && field.type.isPrimitive) {
                throw IllegalArgumentException("Cannot set primitive field '$fieldName' to null")
            }

            field.set(instance, value)
            return
        } catch (_: NoSuchFieldException) {
            cls = cls.superclass
        }
    }
    throw NoSuchFieldException("Field '$fieldName' not found in ${instance.javaClass.name}")
}

/**
 * Searches the class hierarchy of [instance] for a declared field whose type is assignable to
 * [type] and returns its value. Returns `null` if no matching field is found or the field value
 * is not an instance of [type].
 *
 * This is used as a fallback when the exact field name is unknown (e.g. due to Kotlin
 * `@JvmSynthetic internal val` name mangling across different SDK versions).
 */
internal fun <T> findFieldByType(instance: Any, type: Class<T>): T? {
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        for (field in cls.declaredFields) {
            if (type.isAssignableFrom(field.type)) {
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return field.get(instance) as? T
            }
        }
        cls = cls.superclass
    }
    return null
}

/**
 * Intercepts OkHttp calls and traces them using the provided [adapter].
 *
 * TODO: extract to a separate `OkHttp` module.
 */
class OpenTelemetryOkHttpInterceptor(
    private val adapter: LLMTracingAdapter,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): OkHttpResponse {
        if (!TracingManager.isTracingEnabled) {
            return chain.proceed(chain.request())
        }

        val tracer = TracingManager.tracer

        val span = tracer.spanBuilder("").startSpan()
        var isStreamingRequest = false

        span.makeCurrent().use { _ ->
            try {
                // register request
                val (bodyContent, request) = chain.request().withCopiedBodyContent()

                // building request view
                val mediaType = request.body?.contentType()
                val tracyRequest = run {
                    // media type and body content are null when a request has no body content
                    // (e.g., a GET request with query params)
                    val body = when {
                        (bodyContent != null) && (mediaType != null) -> bodyContent.asRequestBody(mediaType)
                        else -> null
                    } ?: TracyHttpRequestBody.Empty

                    body.asRequestView(
                        contentType = mediaType?.toContentType(),
                        url = request.url.toProtocolUrl(),
                        method = request.method,
                    )
                }

                isStreamingRequest = adapter.isStreamingRequest(tracyRequest)
                adapter.registerRequest(span, tracyRequest)

                // register response
                val response = chain.proceed(request)

                return if (isStreamingRequest) {
                    val streamingMarker = JsonObject(mapOf("stream" to JsonPrimitive(true)))
                    val url = request.url.toProtocolUrl()
                    adapter.registerResponse(span, response = response.asResponseView(streamingMarker))

                    wrapStreamingResponse(response, url, span)
                } else {
                    // if the content type is `application/json`, we decode a response body;
                    // otherwise (e.g., when the body is binary), we pass an empty JSON object as the response body.
                    val contentType = response.body?.contentType()
                    val mimeType = if (contentType != null) "${contentType.type}/${contentType.subtype}" else null
                    val responseBody = when (mimeType?.lowercase()) {
                        "application/json" -> try {
                            val peekedBody = response.peekBody(Long.MAX_VALUE).string()
                            Json.decodeFromString<JsonObject>(peekedBody)
                        } catch (_: Exception) {
                            JsonObject(emptyMap())
                        }
                        else -> {
                            val size = response.body?.contentLength() ?: -1L
                            if (size >= 0) {
                                JsonObject(mapOf("_tracy_response_size_bytes" to JsonPrimitive(size)))
                            } else {
                                JsonObject(emptyMap())
                            }
                        }
                    }

                    adapter.registerResponse(span, response = response.asResponseView(responseBody))
                    response
                }
            } catch (e: Exception) {
                span.setStatus(StatusCode.ERROR)
                span.recordException(e)
                throw e
            } finally {
                if (!isStreamingRequest) {
                    span.end()
                }
            }
        }
    }

    private fun wrapStreamingResponse(
        originalResponse: OkHttpResponse,
        url: TracyHttpUrl,
        span: Span,
    ): OkHttpResponse {
        val originalBody = originalResponse.body ?: return originalResponse

        val tracingBody = object : OkHttpResponseBody() {
            private val capturedText = StringBuilder()

            override fun contentType() = originalBody.contentType()
            override fun contentLength() = -1L

            override fun source(): BufferedSource {
                val originalSource = originalBody.source()

                return object : ForwardingSource(originalSource) {
                    private val acc = Buffer()
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val bytesRead = try {
                            super.read(sink, byteCount)
                        } catch (e: Exception) {
                            span.setStatus(StatusCode.ERROR)
                            span.recordException(e)
                            span.end()
                            throw e
                        }

                        if (bytesRead > 0) {
                            val start = sink.size - bytesRead
                            sink.copyTo(acc, start, bytesRead)

                            capturedText.append(acc.readUtf8(bytesRead))
                        }

                        return bytesRead
                    }
                }.buffer()
            }

            override fun close() {
                try {
                    adapter.handleStreaming(span, url, capturedText.toString())
                } finally {
                    span.end()
                }
            }
        }

        return originalResponse.newBuilder().body(tracingBody).build()
    }

    private fun OkHttpRequest.withCopiedBodyContent(): Pair<ByteArray?, OkHttpRequest> {
        val body = this.body ?: return null to this
        val mediaType = body.contentType()

        // read body content
        val content = Buffer().let {
            body.writeTo(it)
            it.readByteArray()
        }

        val request = if (body.isOneShot()) {
            val newBody = content.toRequestBody(mediaType)
            this.newBuilder()
                .method(this.method, newBody)
                .build()
        } else {
            // if the body can be read multiple times,
            // then we can reuse the same request
            this
        }

        return content to request
    }

    private fun OkHttpResponse.asResponseView(body: JsonObject): TracyHttpResponse {
        val response = this
        val mediaType = response.body?.contentType()

        return object : TracyHttpResponse {
            override val contentType = mediaType?.toContentType()
            override val code = response.code
            override val body = TracyHttpResponseBody.Json(body)
            override val url = response.request.url.toProtocolUrl()
            override val requestMethod = response.request.method.uppercase()

            override fun isError() = response.isSuccessful.not()
        }
    }

    private fun ByteArray.asRequestBody(mediaType: MediaType) = this.asRequestBody(
        contentType = mediaType.toContentType(),
        charset = mediaType.charset() ?: Charsets.UTF_8,
    )
}
