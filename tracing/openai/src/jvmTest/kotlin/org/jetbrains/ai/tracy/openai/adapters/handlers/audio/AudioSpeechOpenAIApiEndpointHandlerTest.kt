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
import okio.Buffer
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

            server.enqueue(binaryAudioResponse(size = 512))

            val params = speechParams()
            client.audio().speech().create(params).use { }

            val trace = analyzeSpans().first()

            assertEquals("audio.speech", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("audio.speech", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test output type is set to audio`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(binaryAudioResponse(size = 256))

            val params = speechParams()
            client.audio().speech().create(params).use { }

            val trace = analyzeSpans().first()

            assertEquals("audio", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
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

            server.enqueue(binaryAudioResponse(size = 256))

            val model = SpeechModel.TTS_1
            val params = speechParams(model = model)
            client.audio().speech().create(params).use { }

            val trace = analyzeSpans().first()

            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertTrue(
                trace.attributes[AttributeKey.stringKey("gen_ai.request.model")]?.startsWith(model.asString()) == true,
                "Model attribute should start with '${model.asString()}'"
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

            server.enqueue(binaryAudioResponse(size = 256))

            val voice = SpeechCreateParams.Voice.ALLOY
            val params = speechParams(voice = voice)
            client.audio().speech().create(params).use { }

            val trace = analyzeSpans().first()

            assertEquals(voice.asString(), trace.attributes[AttributeKey.stringKey("tracy.request.voice")])
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

            server.enqueue(binaryAudioResponse(size = 256, contentType = "audio/mpeg"))

            val responseFormat = SpeechCreateParams.ResponseFormat.MP3
            val params = speechParams(responseFormat = responseFormat)
            client.audio().speech().create(params).use { }

            val trace = analyzeSpans().first()

            assertEquals(responseFormat.asString(), trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
        }
    }

    @Test
    fun `test response audio size bytes is traced from content-length header`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            val audioSize = 1024L
            server.enqueue(binaryAudioResponse(size = audioSize.toInt()))

            val params = speechParams()
            client.audio().speech().create(params).use { }

            val trace = analyzeSpans().first()

            val sizeBytes = trace.attributes[AttributeKey.longKey("tracy.response.audio.size_bytes")]
            assertNotNull(sizeBytes, "Response audio size_bytes should be traced")
            assertEquals(audioSize, sizeBytes, "Response audio size_bytes should match Content-Length header")
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

            server.enqueue(binaryAudioResponse(size = 256))

            val speed = 1.5
            val params = SpeechCreateParams.builder()
                .model(SpeechModel.TTS_1)
                .input(SAMPLE_INPUT)
                .voice(SpeechCreateParams.Voice.ALLOY)
                .speed(speed)
                .build()
            client.audio().speech().create(params).use { }

            val trace = analyzeSpans().first()

            val tracedSpeed = trace.attributes[AttributeKey.doubleKey("tracy.request.speed")]
            assertNotNull(tracedSpeed, "Speed should be traced")
            assertEquals(speed, tracedSpeed!!, 0.001)
        }
    }

    // ============ HELPER METHODS ============

    private fun speechParams(
        model: SpeechModel = SpeechModel.TTS_1,
        voice: SpeechCreateParams.Voice = SpeechCreateParams.Voice.ALLOY,
        responseFormat: SpeechCreateParams.ResponseFormat? = null,
    ): SpeechCreateParams {
        val builder = SpeechCreateParams.builder()
            .model(model)
            .input(SAMPLE_INPUT)
            .voice(voice)
        if (responseFormat != null) {
            builder.responseFormat(responseFormat)
        }
        return builder.build()
    }

    private fun binaryAudioResponse(
        size: Int,
        contentType: String = "audio/mpeg",
    ): MockResponse {
        val audioBytes = ByteArray(size) { it.toByte() }
        val buffer = Buffer().write(audioBytes)
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", contentType)
            .setHeader("Content-Length", size.toString())
            .setBody(buffer)
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
        private const val SAMPLE_INPUT = "Hello, this is a test of the text-to-speech API."
    }
}
