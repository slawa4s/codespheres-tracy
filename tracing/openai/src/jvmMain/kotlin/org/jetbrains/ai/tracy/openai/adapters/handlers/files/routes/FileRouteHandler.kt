/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

/**
 * Handles requests and responses for different Files API routes of OpenAI.
 */
internal interface FileRouteHandler {
    fun handleRequest(span: Span, request: TracyHttpRequest)
    fun handleResponse(span: Span, response: TracyHttpResponse)
}

/**
 * Traces a File object with all its fields.
 *
 * File schema:
 * - id: string
 * - created_at: number
 * - expires_at: number
 * - status: string
 * - filename: string
 * - bytes: number
 * - purpose: string
 */
internal fun Span.traceFileModel(body: JsonObject, prefix: String) {
    body["id"]?.let { setAttribute("$prefix.id", it.jsonPrimitive.content) }
    body["created_at"]?.jsonPrimitive?.longOrNull?.let { setAttribute("$prefix.created_at", it) }
    body["expires_at"]?.jsonPrimitive?.longOrNull?.let { setAttribute("$prefix.expires_at", it) }
    body["status"]?.let { setAttribute("$prefix.status", it.jsonPrimitive.content) }
    body["filename"]?.let { setAttribute("$prefix.filename", it.jsonPrimitive.content) }
    body["bytes"]?.jsonPrimitive?.longOrNull?.let { setAttribute("$prefix.size_bytes", it) }
    body["purpose"]?.let { setAttribute("$prefix.purpose", it.jsonPrimitive.content) }
}
