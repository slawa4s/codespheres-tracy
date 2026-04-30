/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files.routes

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Handles requests and responses for different file API routes of OpenAI.
 */
internal interface FileRouteHandler {
    fun handleRequest(span: Span, request: TracyHttpRequest)
    fun handleResponse(span: Span, response: TracyHttpResponse)
}

/**
 * Extracts `file_id` from a path like `/v1/files/{file_id}`.
 */
internal fun extractFileIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val filesIndex = segments.indexOf("files")
    return if (filesIndex != -1 && segments.size > filesIndex + 1) {
        val potentialId = segments[filesIndex + 1]
        if (potentialId.isNotBlank()) potentialId else null
    } else {
        null
    }
}
