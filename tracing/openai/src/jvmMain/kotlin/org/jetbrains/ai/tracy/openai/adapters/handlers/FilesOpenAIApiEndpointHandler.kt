/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Extracts request/response attributes for the OpenAI Files API.
 *
 * Handles four operations derived from the HTTP method and URL:
 * - `POST /v1/files` → `files.create`
 * - `DELETE /v1/files/{file_id}` → `files.delete`
 * - `GET /v1/files/{file_id}` → `files.retrieve`
 * - `GET /v1/files` → `files.list`
 *
 * Sets `openai.api.type = "files"` and `gen_ai.operation.name` on every span in
 * [handleRequestAttributes]. In [handleResponseAttributes] the operation name is
 * re-applied to override the wrong `"file"` value that
 * [OpenAIApiUtils.setCommonResponseAttributes] writes from `body["object"]`.
 *
 * See [Files API Reference](https://platform.openai.com/docs/api-reference/files)
 */
internal class FilesOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "files")
        span.setAttribute(GEN_AI_OPERATION_NAME, deriveOperationName(request.url, request.method))

        // Parse purpose, filename, and file size from multipart form-data (file upload)
        request.body.asFormData()?.let { formData ->
            for (part in formData.parts) {
                when (part.name) {
                    "purpose" -> {
                        val charset = part.contentType?.charset() ?: Charsets.UTF_8
                        span.setAttribute("gen_ai.request.purpose", part.content.toString(charset))
                    }
                    "file" -> {
                        span.setAttribute(
                            AttributeKey.longKey("tracy.request.file.size_bytes"),
                            part.content.size.toLong(),
                        )
                        if (part.filename != null) {
                            span.setAttribute("tracy.request.file.filename", part.filename)
                        }
                    }
                }
            }
        }

        // Parse expires_after fields from JSON body
        request.body.asJson()?.jsonObject?.get("expires_after")?.jsonObject?.let { expiresAfter ->
            expiresAfter["anchor"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.request.expires_after.anchor", it)
            }
            expiresAfter["seconds"]?.jsonPrimitive?.longOrNull?.let {
                span.setAttribute(AttributeKey.longKey("gen_ai.request.expires_after.seconds"), it)
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // Re-set operation name to override the wrong "file" value written by setCommonResponseAttributes
        // (which copies body["object"] verbatim, yielding "file" instead of the correct operation).
        span.setAttribute(GEN_AI_OPERATION_NAME, deriveOperationName(response.url, response.requestMethod))

        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.file.id", it)
        }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute(AttributeKey.longKey("tracy.response.file.created_at"), it)
        }
        body["expires_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute(AttributeKey.longKey("tracy.response.file.expires_at"), it)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Files API does not use server-sent events streaming
        logger.warn { "Files API does not use server-sent events streaming" }
    }

    private fun deriveOperationName(url: TracyHttpUrl, method: String): String {
        val segments = url.pathSegments
        val filesIndex = segments.indexOf("files")
        val hasFileId = filesIndex != -1 &&
                segments.size > (filesIndex + 1) &&
                segments[filesIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasFileId -> "files.create"
            method == "DELETE" -> "files.delete"
            method == "GET" && hasFileId -> "files.retrieve"
            method == "GET" && !hasFileId -> "files.list"
            else -> {
                logger.warn { "Could not derive files operation name for $method ${url.pathSegments.joinToString("/")}" }
                "files.create"
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
