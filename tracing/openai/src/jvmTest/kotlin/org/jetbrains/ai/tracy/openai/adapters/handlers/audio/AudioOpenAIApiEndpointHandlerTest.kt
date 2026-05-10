/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [AudioOpenAIApiEndpointHandler] using [MockWebServer].
 *
 * No real API keys are required — all requests are intercepted by the mock server.
 */
@Tag("openai")
class AudioOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    private fun MockWebServer.makeInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter()).newBuilder().build()

    // ============ POST /audio/transcriptions ============

    @Test
    fun `transcriptionVerboseJsonAttributesAreSet`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "task": "transcribe",
                            "language": "english",
                            "duration": 12.34,
                            "text": "Hello world.",
                            "words": [
                                {"word": "Hello", "start": 0.0, "end": 0.5},
                                {"word": "world.", "start": 0.6, "end": 1.0}
                            ]
                        }
                        """.trimIndent()
                    )
            )

            val audioBytes = ByteArray(512) { it.toByte() }
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "word")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .addFormDataPart("temperature", "0.5")
                .addFormDataPart("prompt", "Previous context")
                .addFormDataPart(
                    "file", "audio.wav",
                    audioBytes.toRequestBody("audio/wav".toMediaType())
                )
                .build()

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/transcriptions"))
                    .post(requestBody)
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()

            assertEquals("audio.transcription", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("whisper-1", trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertEquals("verbose_json", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])

            val granularities = trace.attributes[AttributeKey.stringKey("tracy.request.timestamp_granularities")]
            assertNotNull(granularities)
            assertTrue(granularities!!.contains("word"))
            assertTrue(granularities.contains("segment"))

            assertEquals("0.5", trace.attributes[AttributeKey.stringKey("tracy.request.temperature")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.request.prompt.present")])
            assertEquals(512L, trace.attributes[AttributeKey.longKey("tracy.request.audio.size_bytes")])
            assertEquals("wav", trace.attributes[AttributeKey.stringKey("tracy.request.audio.format")])
            assertEquals(12.34, trace.attributes[AttributeKey.doubleKey("tracy.response.transcription.duration_seconds")])
            assertEquals("english", trace.attributes[AttributeKey.stringKey("tracy.response.transcription.language")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.response.transcription.words.count")])
        }
    }

    // ============ POST /audio/translations ============

    @Test
    fun `translationAttributesAreSet`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "task": "translate",
                            "language": "english",
                            "duration": 8.76,
                            "text": "Hello, how are you?"
                        }
                        """.trimIndent()
                    )
            )

            val audioBytes = ByteArray(256) { it.toByte() }
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("response_format", "json")
                .addFormDataPart(
                    "file", "speech.mp3",
                    audioBytes.toRequestBody("audio/mpeg".toMediaType())
                )
                .build()

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/translations"))
                    .post(requestBody)
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()

            assertEquals("audio.translation", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("whisper-1", trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertEquals("json", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals(256L, trace.attributes[AttributeKey.longKey("tracy.request.audio.size_bytes")])
            assertEquals("mpeg", trace.attributes[AttributeKey.stringKey("tracy.request.audio.format")])
            assertEquals(8.76, trace.attributes[AttributeKey.doubleKey("tracy.response.translation.duration_seconds")])
        }
    }

    // ============ POST /audio/speech ============

    @Test
    fun `speechRequestAttributesAreSet`() = runTest {
        withMockServer { server ->
            val audioBytes = ByteArray(2048) { it.toByte() }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setBody(Buffer().write(audioBytes))
            )

            val requestBody = """{"model":"tts-1","voice":"alloy","response_format":"mp3","speed":1.1,"input":"hello"}"""
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/speech"))
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()

            assertEquals("audio.speech", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("tts-1", trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertEquals("speech", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals("alloy", trace.attributes[AttributeKey.stringKey("tracy.request.voice")])
            assertEquals("mp3", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
            assertEquals(1.1, trace.attributes[AttributeKey.doubleKey("tracy.request.speed")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.response.audio.size_bytes")])
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
