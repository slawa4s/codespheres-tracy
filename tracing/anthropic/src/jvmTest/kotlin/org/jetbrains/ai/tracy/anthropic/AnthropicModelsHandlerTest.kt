/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit-style tests for Anthropic models handler using [okhttp3.mockwebserver.MockWebServer].
 * No real API key or network access required.
 */
@Tag("anthropic")
class AnthropicModelsHandlerTest : BaseAITracingTest() {

    private fun createMockAnthropicClient(baseUrl: String): AnthropicClient =
        AnthropicOkHttpClient.builder()
            .baseUrl(baseUrl)
            .apiKey("mock-api-key")
            .build()

    @Test
    fun `models retrieve reads vision capability from image_input_supported`() = runTest {
        withMockServer { server ->
            val client = createMockAnthropicClient(server.url("/").toString())
                .apply { instrument(this) }

            // Anthropic API represents vision capability as capabilities.image_input.supported
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"claude-haiku-4-5","type":"model","display_name":"Claude Haiku 4.5","created_at":"2024-11-01T00:00:00Z","max_input_tokens":200000,"max_output_tokens":8192,"capabilities":{"image_input":{"supported":true},"batch":true,"citations":false}}""")
            )

            runCatching { client.models().retrieve("claude-haiku-4-5") }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            val span = spans.first()

            assertEquals(
                true,
                span.attributes[AttributeKey.booleanKey("gen_ai.response.model.capabilities.vision")],
                "gen_ai.response.model.capabilities.vision should be true when image_input.supported=true"
            )
        }
    }
}
