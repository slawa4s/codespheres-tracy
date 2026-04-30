/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asFormData
import org.jetbrains.ai.tracy.core.http.protocol.asJson

private val logger = KotlinLogging.logger {}

/**
 * Handles `POST /files` — upload a file.
 *
 * See [Upload file](https://platform.openai.com/docs/api-reference/files/create)
 */
internal class UploadFileHandler : FileRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val formData = request.body.asFormData()
        if (formData != null) {
            for (part in formData.parts) {
                when (part.name) {
                    "purpose" -> {
                        val charset = part.contentType?.charset() ?: Charsets.UTF_8
                        span.setAttribute("tracy.request.purpose", part.content.toString(charset))
                    }
                    "file" -> {
                        if (part.filename != null) {
                            span.setAttribute("tracy.request.filename", part.filename)
                        }
                    }
                }
            }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.id", it) }
        body["filename"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.filename", it) }
        body["bytes"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.bytes", it) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.file.created_at", it) }
        body["purpose"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.purpose", it) }
        body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.response.file.status", it) }
    }
}
