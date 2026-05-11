/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.videos.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.videos.VideosOpenAIApiEndpointHandler

private val logger = KotlinLogging.logger {}

/**
 * Handles [VideosOpenAIApiEndpointHandler.VideoRoute.GET_VIDEO] endpoint: `GET /videos/{video_id}`.
 */
internal class GetVideoHandler : VideoRouteHandler {
    /**
     * Request: Path parameter video_id
     */
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val videoId = extractVideoIdFromPath(request.url)
        if (videoId != null) {
            span.setAttribute("gen_ai.request.video.requested_id", videoId)
        } else {
            logger.warn { "Failed to extract video ID from URL: ${request.url}" }
        }
    }

    /**
     * Response: Video model
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let { span.setAttribute("gen_ai.response.id", it.jsonPrimitive.content) }
        body["model"]?.let {
            span.setAttribute("gen_ai.response.model", it.jsonPrimitive.content)
            span.setAttribute("tracy.response.model", it.jsonPrimitive.content)
        }
        body["object"]?.let { span.setAttribute("tracy.response.object", it.jsonPrimitive.content) }
        body["status"]?.let { span.setAttribute("tracy.response.status", it.jsonPrimitive.content) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.created_at", it) }
        body["progress"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.progress", it) }
        body["seconds"]?.let { span.setAttribute("tracy.response.seconds", it.jsonPrimitive.content) }
        body["size"]?.let { span.setAttribute("tracy.response.size", it.jsonPrimitive.content) }
    }
}
