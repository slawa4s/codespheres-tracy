/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import com.openai.models.videos.VideoListParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for [org.jetbrains.ai.tracy.openai.adapters.handlers.videos.routes.ListVideosHandler].
 * Uses [okhttp3.mockwebserver.MockWebServer] — no real API calls are made.
 */
@Tag("openai")
class ListVideosHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun listVideosHandler_setsTracyNamespacedPaginationAttributes() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val limit = 10L
            val order = "asc"
            val after = "video_cursor_abc"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "object": "list",
                          "data": [
                            {
                              "id": "video_first_abc",
                              "object": "video",
                              "status": "completed",
                              "created_at": 1710000000,
                              "model": "sora-2",
                              "prompt": "Test video"
                            }
                          ],
                          "first_id": "video_first_abc",
                          "last_id": "video_last_xyz",
                          "has_more": true
                        }
                        """.trimIndent()
                    )
            )

            val listParams = VideoListParams.builder()
                .limit(limit)
                .order(VideoListParams.Order.ASC)
                .after(after)
                .build()

            client.videos().list(listParams)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(limit.toString(), trace.attributes[AttributeKey.stringKey("tracy.request.limit")])
            assertEquals(order, trace.attributes[AttributeKey.stringKey("tracy.request.order")])
            assertEquals(after, trace.attributes[AttributeKey.stringKey("tracy.request.after")])
            assertEquals("video_first_abc", trace.attributes[AttributeKey.stringKey("tracy.response.first_id")])
            assertEquals("video_last_xyz", trace.attributes[AttributeKey.stringKey("tracy.response.last_id")])
            assertNotNull(trace.attributes[AttributeKey.booleanKey("tracy.response.has_more")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.response.has_more")])
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
