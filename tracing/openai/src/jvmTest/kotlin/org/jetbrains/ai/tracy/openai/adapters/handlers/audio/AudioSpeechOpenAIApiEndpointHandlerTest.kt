/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import com.openai.models.audio.speech.SpeechCreateParams
import com.openai.models.audio.speech.SpeechModel
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * These tests use [okhttp3.mockwebserver.MockWebServer] and a mock OpenAI API key,
 * so they do not require access to the real OpenAI Audio Speech API.
 */
@Tag("openai")
class AudioSpeechOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `test operation name and api type are set for audio speech`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(speechResponse())
            client.audio().speech().create(speechParams()).close()

            val trace = analyzeSpans().first()

            assertEquals("audio.speech", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test output type is set to speech`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(speechResponse())
            client.audio().speech().create(speechParams()).close()

            val trace = analyzeSpans().first()

            assertEquals("speech", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `test model is traced from request body`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(speechResponse())
            client.audio().speech().create(speechParams(model = SpeechModel.TTS_1)).close()

            val trace = analyzeSpans().first()

            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertEquals(
                SpeechModel.TTS_1.asString(),
                trace.attributes[AttributeKey.stringKey("gen_ai.request.model")]
            )
        }
    }

    @Test
    fun `test voice is traced from request body`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(speechResponse())
            client.audio().speech().create(
                speechParams(voice = SpeechCreateParams.Voice.ALLOY)
            ).close()

            val trace = analyzeSpans().first()

            assertEquals("alloy", trace.attributes[AttributeKey.stringKey("tracy.request.voice")])
        }
    }

    @Test
    fun `test response_format is traced from request body`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(speechResponse(contentType = "audio/opus"))
            client.audio().speech().create(
                speechParams(responseFormat = SpeechCreateParams.ResponseFormat.OPUS)
            ).close()

            val trace = analyzeSpans().first()

            assertEquals("opus", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
        }
    }

    @Test
    fun `test speed is traced from request body`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(speechResponse())
            client.audio().speech().create(speechParams(speed = 1.5)).close()

            val trace = analyzeSpans().first()

            assertNotNull(
                trace.attributes[AttributeKey.doubleKey("tracy.request.speed")],
                "Speed should be traced"
            )
            assertEquals(1.5, trace.attributes[AttributeKey.doubleKey("tracy.request.speed")])
        }
    }

    @Test
    fun `test response audio size bytes is traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            val audioBytes = ByteArray(1024) { it.toByte() }
            server.enqueue(speechResponse(body = audioBytes))
            client.audio().speech().create(speechParams()).close()

            val trace = analyzeSpans().first()

            val sizeBytes = trace.attributes[AttributeKey.longKey("tracy.response.audio.size_bytes")]
            assertNotNull(sizeBytes, "Response audio size_bytes should be traced")
            assertEquals(1024L, sizeBytes)
        }
    }

    // ============ HELPER METHODS ============

    private fun speechParams(
        model: SpeechModel = SpeechModel.TTS_1,
        voice: SpeechCreateParams.Voice = SpeechCreateParams.Voice.ALLOY,
        input: String = "Hello world",
        responseFormat: SpeechCreateParams.ResponseFormat? = null,
        speed: Double? = null,
    ): SpeechCreateParams {
        val builder = SpeechCreateParams.builder()
            .model(model)
            .voice(voice)
            .input(input)
        if (responseFormat != null) {
            builder.responseFormat(responseFormat)
        }
        if (speed != null) {
            builder.speed(speed)
        }
        return builder.build()
    }

    private fun speechResponse(
        body: ByteArray = ByteArray(256) { 0.toByte() },
        contentType: String = "audio/mpeg",
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", contentType)
            .setBody(okio.Buffer().write(body))
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
