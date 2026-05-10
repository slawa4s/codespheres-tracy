/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for `ModerationsOpenAIApiEndpointHandler` via [MockWebServer].
 *
 * No real API keys are required — all requests are intercepted by the mock server.
 */
@Tag("openai")
class OpenAIModerationsTracingTest : BaseOpenAITracingTest() {

    private fun MockWebServer.makeInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter()).newBuilder().build()

    private fun jsonBody(json: String) =
        json.toRequestBody("application/json".toMediaType())

    private val basicResponse = """
        {
          "id": "modr-abc123",
          "model": "omni-moderation-latest",
          "results": [
            {
              "flagged": true,
              "categories": {
                "hate": false,
                "hate/threatening": true,
                "self-harm": false,
                "sexual": false,
                "sexual/minors": false,
                "violence": true,
                "violence/graphic": false
              },
              "category_scores": {
                "hate": 0.05,
                "hate/threatening": 0.72,
                "self-harm": 0.01,
                "sexual": 0.01,
                "sexual/minors": 0.0,
                "violence": 0.95,
                "violence/graphic": 0.02
              }
            }
          ]
        }
    """.trimIndent()

    private val multimodalResponse = """
        {
          "id": "modr-def456",
          "model": "omni-moderation-latest",
          "results": [
            {
              "flagged": false,
              "categories": {
                "hate": false,
                "violence": false
              },
              "category_scores": {
                "hate": 0.01,
                "violence": 0.02
              },
              "category_applied_input_types": {
                "hate": ["text"],
                "violence": ["text", "image"]
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `moderationsBasicSetsOperationName`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(basicResponse)
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post(jsonBody("""{"input":"I want to kill them.","model":"omni-moderation-latest"}"""))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("moderations", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `moderationsBasicSetsApiType`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(basicResponse)
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post(jsonBody("""{"input":"I want to kill them."}"""))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("moderations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `moderationsBasicSetsInputTypeString`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(basicResponse)
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post(jsonBody("""{"input":"I want to kill them."}"""))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("string", trace.attributes[AttributeKey.stringKey("tracy.request.input.type")])
        }
    }

    @Test
    fun `moderationsMultimodalSetsInputTypeMultimodal`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(multimodalResponse)
            )
            val requestJson = """
                {
                  "model": "omni-moderation-latest",
                  "input": [
                    {"type": "text", "text": "kill"},
                    {"type": "image_url", "image_url": {"url": "https://example.com/img.png"}}
                  ]
                }
            """.trimIndent()
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post(jsonBody(requestJson))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("multimodal", trace.attributes[AttributeKey.stringKey("tracy.request.input.type")])
        }
    }

    @Test
    fun `moderationsResponseSetsResultCount`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(basicResponse)
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post(jsonBody("""{"input":"test"}"""))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.response.results.count")])
        }
    }

    @Test
    fun `moderationsResponseSetsFlaggedAndCategories`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(basicResponse)
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post(jsonBody("""{"input":"test"}"""))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("true", trace.attributes[AttributeKey.stringKey("tracy.response.results.flagged")])
            val categories = trace.attributes[AttributeKey.stringKey("tracy.response.results.categories")]
            assertEquals(true, categories?.isNotEmpty())
            val scores = trace.attributes[AttributeKey.stringKey("tracy.response.results.category_scores")]
            assertEquals(true, scores?.isNotEmpty())
        }
    }

    @Test
    fun `moderationsMultimodalSetsCategoryAppliedInputTypes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(multimodalResponse)
            )
            val requestJson = """
                {
                  "model": "omni-moderation-latest",
                  "input": [
                    {"type": "text", "text": "test"}
                  ]
                }
            """.trimIndent()
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post(jsonBody(requestJson))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            val appliedTypes = trace.attributes[AttributeKey.stringKey("tracy.response.results.category_applied_input_types")]
            assertEquals(true, appliedTypes?.isNotEmpty())
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
