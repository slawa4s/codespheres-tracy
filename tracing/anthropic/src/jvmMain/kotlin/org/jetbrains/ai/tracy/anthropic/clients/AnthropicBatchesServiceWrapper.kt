/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.clients

import com.anthropic.core.RequestOptions
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.batches.BatchCreateParams
import com.anthropic.models.messages.batches.MessageBatch
import com.anthropic.services.blocking.messages.BatchService
import io.opentelemetry.api.trace.StatusCode
import org.jetbrains.ai.tracy.core.TracingManager
import java.net.URI

/**
 * Wraps [BatchService] to ensure that an OTel span is emitted for [create] calls that fail with
 * a client-side validation error *before* any HTTP request is issued.
 *
 * The Anthropic SDK may validate the `requests` array before reaching the OkHttp layer; when
 * validation fails the SDK throws immediately and Tracy's OkHttp interceptor never fires, so no
 * span would otherwise be recorded.  This wrapper fills that gap by emitting a span only in the
 * `catch (e: Exception)` branch, *excluding* [AnthropicServiceException] (which is thrown after
 * an HTTP round-trip and is therefore already covered by the OkHttp interceptor span).
 *
 * For successful calls and HTTP-level errors ([AnthropicServiceException]), the wrapper is
 * transparent: no span is opened, and the OkHttp interceptor span is the sole recorded span.
 *
 * Attributes recorded on the pre-HTTP error span:
 * - `gen_ai.provider.name` = `"anthropic"`
 * - `anthropic.api.type` = `"batches"`
 * - `gen_ai.operation.name` = `"batches.create"`
 * - `server.address` / `server.port` derived from the client's configured base URL
 * - `error.type` – simple class name of the caught exception
 *
 * All other [BatchService] methods are forwarded unchanged to the [delegate].
 */
internal class AnthropicBatchesServiceWrapper(
    private val delegate: BatchService,
    private val baseUrl: String,
) : BatchService by delegate {

    override fun create(params: BatchCreateParams): MessageBatch =
        create(params, RequestOptions.none())

    override fun create(params: BatchCreateParams, requestOptions: RequestOptions): MessageBatch {
        if (!TracingManager.isTracingEnabled) {
            return delegate.create(params, requestOptions)
        }

        return try {
            delegate.create(params, requestOptions)
        } catch (e: Exception) {
            // AnthropicServiceException is thrown after an HTTP round-trip; the OkHttp interceptor
            // has already recorded a span for it, so skip creating a second one.
            if (e is AnthropicServiceException) throw e

            // Pre-HTTP client-side exception: the OkHttp interceptor never fired, so emit a span.
            val (host, port) = resolveHostAndPort(baseUrl)
            val span = TracingManager.tracer
                .spanBuilder("Anthropic-generation")
                .startSpan()
            span.setAttribute("gen_ai.provider.name", "anthropic")
            span.setAttribute("anthropic.api.type", "batches")
            span.setAttribute("gen_ai.operation.name", "batches.create")
            span.setAttribute("server.address", host)
            span.setAttribute("server.port", port)
            span.setStatus(StatusCode.ERROR)
            span.setAttribute("error.type", e.javaClass.simpleName)
            span.end()
            throw e
        }
    }

    private fun resolveHostAndPort(baseUrl: String): Pair<String, Long> {
        return try {
            val uri = URI(baseUrl)
            val host = uri.host ?: baseUrl
            val port = when {
                uri.port > 0 -> uri.port.toLong()
                uri.scheme?.lowercase() == "https" -> 443L
                else -> 80L
            }
            host to port
        } catch (_: Exception) {
            baseUrl to 443L
        }
    }
}
