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
 * Wraps [BatchService] to ensure that an OTel span is opened around every [create] call,
 * including calls that fail with a client-side validation error before any HTTP request is issued.
 *
 * The Anthropic SDK validates the `requests` array before reaching the OkHttp layer; when the
 * array is empty the SDK throws immediately and Tracy's OkHttp interceptor never fires, so no span
 * would otherwise be recorded.  This wrapper guarantees a span is emitted for every [create]
 * invocation.
 *
 * Attributes recorded on every span:
 * - `gen_ai.provider.name` = `"anthropic"`
 * - `anthropic.api.type` = `"batches"`
 * - `gen_ai.operation.name` = `"batches.create"`
 * - `server.address` / `server.port` derived from the client's configured base URL
 *
 * Additional attributes recorded on error:
 * - `error.type` – simple class name of the caught exception
 * - `http.response.status_code` – HTTP status code (only when an [AnthropicServiceException] is thrown)
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

        val (host, port) = resolveHostAndPort(baseUrl)

        val span = TracingManager.tracer
            .spanBuilder("Anthropic-generation")
            .startSpan()

        span.setAttribute("gen_ai.provider.name", "anthropic")
        span.setAttribute("anthropic.api.type", "batches")
        span.setAttribute("gen_ai.operation.name", "batches.create")
        span.setAttribute("server.address", host)
        span.setAttribute("server.port", port)

        return try {
            delegate.create(params, requestOptions)
        } catch (e: AnthropicServiceException) {
            span.setStatus(StatusCode.ERROR)
            span.setAttribute("error.type", e.javaClass.simpleName)
            span.setAttribute("http.response.status_code", e.statusCode().toLong())
            throw e
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR)
            span.setAttribute("error.type", e.javaClass.simpleName)
            throw e
        } finally {
            span.end()
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
