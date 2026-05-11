/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.videos

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.videos.routes.*

/**
 * Handler for OpenAI Videos API (Sora video generation).
 *
 * The Videos API provides multiple endpoints for video operations:
 * 1. `POST /videos` - Create video generation job (returns Video with initial status)
 * 2. `GET /videos/{video_id}` - Get video job status (returns Video)
 * 3. `GET /videos` - List all videos with pagination (returns array of Videos)
 * 4. `DELETE /videos/{video_id}` - Delete video (returns deletion confirmation)
 * 5. `GET /videos/{video_id}/content` - Download video MP4 (returns binary stream)
 * 6. `POST /videos/{video_id}/remix` - Remix existing video (returns new Video)
 *
 * This handler detects the specific route and traces accordingly.
 *
 * See [Videos API Reference](https://platform.openai.com/docs/api-reference/videos)
 */
internal class VideosOpenAIApiEndpointHandler(
    private val extractor: MediaContentExtractor
) : EndpointApiHandler {
    /**
     * Registry of route handlers, initialized lazily to avoid creating handlers until needed.
     */
    private val routeHandlers: Map<VideoRoute, VideoRouteHandler> by lazy {
        mapOf(
            VideoRoute.CREATE to CreateVideoHandler(extractor),
            VideoRoute.GET_VIDEO to GetVideoHandler(),
            VideoRoute.DELETE to DeleteVideoHandler(),
            VideoRoute.LIST to ListVideosHandler(),
            VideoRoute.VIDEO_CONTENT to GetVideoContentHandler(),
            VideoRoute.REMIX to RemixVideoHandler()
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationNameFor(route))
        routeHandlers[route]?.handleRequest(span, request)

        if (request.method == "GET") {
            val params = request.url.parameters
            params.queryParameter("limit")?.toLongOrNull()?.let { span.setAttribute("tracy.request.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
            params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        routeHandlers[route]?.handleResponse(span, response)

        val body = response.body.asJson()?.jsonObject ?: return

        val objectType = body["object"]?.jsonPrimitive?.contentOrNull
        objectType?.let { span.setAttribute("tracy.response.object", it) }

        when (objectType) {
            "video", "video.deleted" -> {
                body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.id", it) }
                body["status"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.status", it) }
                body["model"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.model", it) }
                body["created_at"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.created_at", it) }
                body["progress"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.progress", it) }
                body["seconds"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute("tracy.response.seconds", it.toLong()) }
                body["size"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.size", it) }
                body["deleted"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.deleted", it) }
            }
            "list" -> {
                val data = body["data"]?.jsonArray
                span.setAttribute("tracy.response.list.count", (data?.size ?: 0).toLong())
                body["has_more"]?.jsonPrimitive?.booleanOrNull?.let { span.setAttribute("tracy.response.has_more", it) }
            }
            else -> {
                // fallback: try to extract id and status
                body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.response.id", it) }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Videos API doesn't support SSE streaming for creation
        // Content download is binary streaming handled separately
        logger.warn { "Videos API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific video endpoint is being called based on the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): VideoRoute {
        val segments = url.pathSegments
        // find index of "videos" segment
        val videosIndex = segments.indexOf("videos")
        if (videosIndex == -1) {
            // fallback
            logger.warn { "Failed to detect video route. Endpoint has no `videos` path segment: ${url.pathSegments.joinToString(separator = "/")}" }
            return VideoRoute.CREATE
        }
        val containsVideoId = segments.size > (videosIndex + 1) &&
                    segments[videosIndex + 1].isNotBlank() &&
                    segments[videosIndex + 1] != "videos"

        return when {
            method == "POST" && !containsVideoId -> VideoRoute.CREATE
            method == "POST" && segments.contains("remix") -> VideoRoute.REMIX
            method == "GET" && segments.contains("content") -> VideoRoute.VIDEO_CONTENT
            method == "GET" && containsVideoId -> VideoRoute.GET_VIDEO
            method == "GET" && !containsVideoId -> VideoRoute.LIST
            method == "DELETE" && containsVideoId -> VideoRoute.DELETE
            // fallback
            else -> {
                logger.warn { "Failed to detect video route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                VideoRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different video API routes.
     */
    private enum class VideoRoute {
        CREATE,   // POST /videos
        GET_VIDEO,      // GET /videos/{video_id}
        DELETE,   // DELETE /videos/{video_id}
        LIST,     // GET /videos
        VIDEO_CONTENT,  // GET /videos/{video_id}/content
        REMIX     // POST /videos/{video_id}/remix
    }

    private fun operationNameFor(route: VideoRoute): String = when (route) {
        VideoRoute.CREATE -> "videos.create"
        VideoRoute.GET_VIDEO -> "videos.retrieve"
        VideoRoute.DELETE -> "videos.delete"
        VideoRoute.LIST -> "videos.list"
        VideoRoute.VIDEO_CONTENT -> "videos.content"
        VideoRoute.REMIX -> "videos.remix"
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
