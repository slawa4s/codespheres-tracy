/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.adapters.handlers

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

/**
 * Handles a single API route within an [EndpointApiHandler] that dispatches across
 * multiple routes of the same logical endpoint family.
 */
interface RouteHandler {
    fun handleRequest(span: Span, request: TracyHttpRequest)
    fun handleResponse(span: Span, response: TracyHttpResponse)
}
