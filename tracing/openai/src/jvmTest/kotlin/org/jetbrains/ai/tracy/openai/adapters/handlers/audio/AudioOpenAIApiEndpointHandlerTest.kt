/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import com.openai.models.audio.AudioModel
import com.openai.models.audio.speech.SpeechCreateParams
import com.openai.models.audio.speech.SpeechModel
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import com.openai.models.audio.translations.TranslationCreateParams
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [AudioOpenAIApiEndpointHandler].
 *
 * These tests use [okhttp3.mockwebserver.MockWebServer] and a mock OpenAI API key, so they
 * do not require access to the real OpenAI Audio API.
 */
@Tag("openai")
class AudioOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `transcription request gets traced with audio metadata`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val expectedText = "Hello world from the transcription test."
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{ "text": "$expectedText" }""")
            )

            val audio = readResource("lofi.wav")
            val params = TranscriptionCreateParams.builder()
                .file(audio.readAllBytes())
                .model(AudioModel.WHISPER_1)
                .build()

            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()
            assertEquals("audio.transcription", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(AudioModel.WHISPER_1.asString(), trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            val sizeBytes = trace.attributes[AttributeKey.longKey("gen_ai.request.audio.size_bytes")]
            assertNotNull(sizeBytes, "Expected gen_ai.request.audio.size_bytes to be set")
            assertTrue(sizeBytes > 0L, "Expected audio size to be positive")
            assertEquals(expectedText, trace.attributes[AttributeKey.stringKey("gen_ai.response.text")])
        }
    }

    @Test
    fun `speech request gets traced with voice and binary audio response size`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            // Tiny stub binary audio body so the handler can read Content-Length
            val audioBytes = ByteArray(64) { it.toByte() }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setHeader("Content-Length", audioBytes.size.toString())
                    .setBody(okio.Buffer().write(audioBytes))
            )

            val params = SpeechCreateParams.builder()
                .input("Hello, this is a test.")
                .model(SpeechModel.TTS_1)
                .voice(SpeechCreateParams.Voice.ALLOY)
                .responseFormat(SpeechCreateParams.ResponseFormat.MP3)
                .build()

            client.audio().speech().create(params).close()

            val trace = analyzeSpans().first()
            assertEquals("audio.speech", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("alloy", trace.attributes[AttributeKey.stringKey("gen_ai.request.voice")])
            assertEquals("mp3", trace.attributes[AttributeKey.stringKey("gen_ai.request.response_format")])
            assertEquals("speech", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals(
                audioBytes.size.toLong(),
                trace.attributes[AttributeKey.longKey("gen_ai.response.audio.size_bytes")],
            )
        }
    }

    @Test
    fun `translation error response is traced with operation name and error status`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "error": {
                            "message": "Invalid model",
                            "type": "invalid_request_error",
                            "param": "model",
                            "code": "model_not_found"
                          }
                        }
                        """.trimIndent()
                    )
            )

            val audio = readResource("lofi.wav")
            val params = TranslationCreateParams.builder()
                .file(audio.readAllBytes())
                .model(AudioModel.WHISPER_1)
                .build()

            try {
                client.audio().translations().create(params)
            } catch (_: Exception) {
                // expected: server returned an error
            }

            val trace = analyzeSpans().first()
            assertEquals("audio.translation", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals(400L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-api-key"
    }
}
