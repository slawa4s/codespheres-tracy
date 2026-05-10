/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

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
 * Tests for [AudioSpeechOpenAIApiEndpointHandler] using [MockWebServer].
 *
 * No real network calls or API keys are required.
 */
@Tag("openai")
class AudioSpeechOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `audio speech sets api type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueueBinaryResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/speech"))
                    .post(buildSpeechRequestBody())
                    .build()
            ).execute().use { it.body?.bytes() }

            val trace = analyzeSpans().first()
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("audio.speech", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `audio speech sets output type to speech`() = runTest {
        withMockServer { server ->
            server.enqueueBinaryResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/speech"))
                    .post(buildSpeechRequestBody())
                    .build()
            ).execute().use { it.body?.bytes() }

            val trace = analyzeSpans().first()
            assertEquals("speech", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `audio speech extracts model from request body`() = runTest {
        withMockServer { server ->
            server.enqueueBinaryResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/speech"))
                    .post(buildSpeechRequestBody(model = "tts-1-hd"))
                    .build()
            ).execute().use { it.body?.bytes() }

            val trace = analyzeSpans().first()
            assertEquals("tts-1-hd", trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    @Test
    fun `audio speech extracts voice from request body`() = runTest {
        withMockServer { server ->
            server.enqueueBinaryResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/speech"))
                    .post(buildSpeechRequestBody(voice = "nova"))
                    .build()
            ).execute().use { it.body?.bytes() }

            val trace = analyzeSpans().first()
            assertEquals("nova", trace.attributes[AttributeKey.stringKey("tracy.request.voice")])
        }
    }

    @Test
    fun `audio speech extracts response format and speed from request body`() = runTest {
        withMockServer { server ->
            server.enqueueBinaryResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/speech"))
                    .post(buildSpeechRequestBody(responseFormat = "opus", speed = 1.5))
                    .build()
            ).execute().use { it.body?.bytes() }

            val trace = analyzeSpans().first()
            assertEquals("opus", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
            assertEquals(1.5, trace.attributes[AttributeKey.doubleKey("tracy.request.speed")])
        }
    }

    @Test
    fun `audio speech does not conflict with audio transcription routing`() = runTest {
        withMockServer { server ->
            server.enqueueBinaryResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/speech"))
                    .post(buildSpeechRequestBody())
                    .build()
            ).execute().use { it.body?.bytes() }

            val trace = analyzeSpans().first()
            assertEquals("audio.speech", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ===== Helpers =====

    private fun buildClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter())

    private fun buildSpeechRequestBody(
        model: String = "tts-1",
        voice: String = "alloy",
        responseFormat: String = "mp3",
        speed: Double = 1.0,
    ): okhttp3.RequestBody {
        val json = """{"model":"$model","input":"Hello, world!","voice":"$voice","response_format":"$responseFormat","speed":$speed}"""
        return json.toRequestBody("application/json".toMediaType())
    }

    private fun MockWebServer.enqueueBinaryResponse() {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody("FAKE_AUDIO_BYTES")
        )
    }
}
