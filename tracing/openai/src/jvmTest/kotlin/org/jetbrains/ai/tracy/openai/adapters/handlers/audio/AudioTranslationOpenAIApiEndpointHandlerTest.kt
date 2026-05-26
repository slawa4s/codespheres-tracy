/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import com.openai.core.MultipartField
import com.openai.models.audio.AudioModel
import com.openai.models.audio.translations.TranslationCreateParams
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.policy.ContentCapturePolicy
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * These tests use [okhttp3.mockwebserver.MockWebServer] and a mock OpenAI API key,
 * so they do not require access to the real OpenAI Audio API.
 */
@Tag("openai")
class AudioTranslationOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `test operation name and api type are set for audio translation`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranslationResponse())

            val params = translationParams()
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertEquals("audio.translation", trace.attributes[GEN_AI_OPERATION_NAME])
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test model is traced from request form data`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranslationResponse())

            val model = AudioModel.WHISPER_1
            val params = translationParams(model = model)
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertTrue(
                trace.attributes[AttributeKey.stringKey("gen_ai.request.model")]?.startsWith(model.asString()) == true,
                "Model attribute should start with '${model.asString()}'"
            )
        }
    }

    @Test
    fun `test audio file attributes are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranslationResponse())

            val params = translationParams()
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            val sizeBytes = trace.attributes[AttributeKey.longKey("tracy.request.audio.size_bytes")]
            assertNotNull(sizeBytes, "Audio size_bytes should be traced")
            assertTrue(sizeBytes!! > 0, "Audio size_bytes should be positive")

            assertEquals(
                AUDIO_CONTENT_TYPE,
                trace.attributes[AttributeKey.stringKey("tracy.request.audio.mime_type")]
            )
        }
    }

    @Test
    fun `test response_format is traced and output type set to json for json format`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranslationResponse())

            val params = translationParams(responseFormat = TranslationCreateParams.ResponseFormat.JSON)
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertEquals("json", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `test response_format is traced and output type set to json for verbose_json format`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(verboseJsonTranslationResponse())

            val params = translationParams(responseFormat = TranslationCreateParams.ResponseFormat.VERBOSE_JSON)
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertEquals("verbose_json", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `test verbose json response top-level fields are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            TracingManager.withCapturingPolicy(
                ContentCapturePolicy(captureInputs = true, captureOutputs = true)
            )

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(verboseJsonTranslationResponse(duration = 7.5, language = "english"))

            val params = translationParams(responseFormat = TranslationCreateParams.ResponseFormat.VERBOSE_JSON)
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                7.5,
                trace.attributes[AttributeKey.doubleKey("tracy.response.translation.duration_seconds")]
            )
            assertEquals(
                "english",
                trace.attributes[AttributeKey.stringKey("tracy.response.translation.language")]
            )
            assertEquals(
                "Hello world.",
                trace.attributes[AttributeKey.stringKey("tracy.response.translation.text")]
            )
        }
    }

    @Test
    fun `test response text is redacted when output capture is disabled`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            TracingManager.withCapturingPolicy(
                ContentCapturePolicy(captureInputs = false, captureOutputs = false)
            )

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranslationResponse())

            val params = translationParams()
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                "REDACTED",
                trace.attributes[AttributeKey.stringKey("tracy.response.translation.text")]
            )
        }
    }

    @Test
    fun `test response text is traced verbatim when output capture is enabled`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            TracingManager.withCapturingPolicy(
                ContentCapturePolicy(captureInputs = true, captureOutputs = true)
            )

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranslationResponse())

            val params = translationParams()
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                "Hello world.",
                trace.attributes[AttributeKey.stringKey("tracy.response.translation.text")]
            )
        }
    }

    @Test
    fun `test segments are traced per-index with all fields`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            TracingManager.withCapturingPolicy(
                ContentCapturePolicy(captureInputs = true, captureOutputs = true)
            )

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "task": "translate",
                          "language": "english",
                          "duration": 5.0,
                          "text": "Hello world.",
                          "segments": [
                            {
                              "id": 0,
                              "avg_logprob": -0.2,
                              "compression_ratio": 1.3,
                              "end": 2.5,
                              "no_speech_prob": 0.01,
                              "seek": 0,
                              "start": 0.0,
                              "temperature": 0.0,
                              "text": "Hello",
                              "tokens": [123, 456]
                            },
                            {
                              "id": 1,
                              "avg_logprob": -0.3,
                              "compression_ratio": 1.4,
                              "end": 5.0,
                              "no_speech_prob": 0.02,
                              "seek": 250,
                              "start": 2.5,
                              "temperature": 0.0,
                              "text": "world.",
                              "tokens": [789]
                            }
                          ]
                        }
                        """.trimIndent()
                    )
            )

            val params = translationParams(responseFormat = TranslationCreateParams.ResponseFormat.VERBOSE_JSON)
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            // Segment 0
            assertEquals(0L, trace.attributes[AttributeKey.longKey("tracy.response.segments.0.id")])
            assertEquals(-0.2, trace.attributes[AttributeKey.doubleKey("tracy.response.segments.0.avg_logprob")])
            assertEquals(1.3, trace.attributes[AttributeKey.doubleKey("tracy.response.segments.0.compression_ratio")])
            assertEquals(2.5, trace.attributes[AttributeKey.doubleKey("tracy.response.segments.0.end")])
            assertEquals(0.01, trace.attributes[AttributeKey.doubleKey("tracy.response.segments.0.no_speech_prob")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("tracy.response.segments.0.seek")])
            assertEquals(0.0, trace.attributes[AttributeKey.doubleKey("tracy.response.segments.0.start")])
            assertEquals(0.0, trace.attributes[AttributeKey.doubleKey("tracy.response.segments.0.temperature")])
            assertEquals("Hello", trace.attributes[AttributeKey.stringKey("tracy.response.segments.0.text")])
            assertEquals("[123,456]", trace.attributes[AttributeKey.stringKey("tracy.response.segments.0.tokens")])

            // Segment 1
            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.response.segments.1.id")])
            assertEquals("world.", trace.attributes[AttributeKey.stringKey("tracy.response.segments.1.text")])
            assertEquals("[789]", trace.attributes[AttributeKey.stringKey("tracy.response.segments.1.tokens")])
        }
    }

    @Test
    fun `test segment text is redacted when output capture is disabled`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            TracingManager.withCapturingPolicy(
                ContentCapturePolicy(captureInputs = false, captureOutputs = false)
            )

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "text": "Hello",
                          "segments": [
                            {"id": 0, "text": "Hello"}
                          ]
                        }
                        """.trimIndent()
                    )
            )

            val params = translationParams(responseFormat = TranslationCreateParams.ResponseFormat.VERBOSE_JSON)
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                "REDACTED",
                trace.attributes[AttributeKey.stringKey("tracy.response.segments.0.text")]
            )
        }
    }

    @Test
    fun `test temperature is traced from request form data`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranslationResponse())

            val params = TranslationCreateParams.builder()
                .file(audioFile())
                .model(AudioModel.WHISPER_1)
                .temperature(0.5)
                .build()
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                0.5,
                trace.attributes[AttributeKey.doubleKey("tracy.request.temperature")]
            )
        }
    }

    @Test
    fun `test prompt is redacted when input capture is disabled`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            TracingManager.withCapturingPolicy(
                ContentCapturePolicy(captureInputs = false, captureOutputs = false)
            )

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranslationResponse())

            val params = TranslationCreateParams.builder()
                .file(audioFile())
                .model(AudioModel.WHISPER_1)
                .prompt("Translate the following audio to English.")
                .build()
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                "REDACTED",
                trace.attributes[AttributeKey.stringKey("tracy.request.prompt")]
            )
        }
    }

    @Test
    fun `test prompt is traced verbatim when input capture is enabled`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            TracingManager.withCapturingPolicy(
                ContentCapturePolicy(captureInputs = true, captureOutputs = true)
            )

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranslationResponse())

            val params = TranslationCreateParams.builder()
                .file(audioFile())
                .model(AudioModel.WHISPER_1)
                .prompt("Translate the following audio to English.")
                .build()
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                "Translate the following audio to English.",
                trace.attributes[AttributeKey.stringKey("tracy.request.prompt")]
            )
        }
    }

    // ============ HELPER METHODS ============

    private fun audioFile(): MultipartField<InputStream> =
        MultipartField.builder<InputStream>()
            .value(readResource(AUDIO_FILE))
            .contentType(AUDIO_CONTENT_TYPE)
            .filename(AUDIO_FILE)
            .build()

    private fun translationParams(
        model: AudioModel = AudioModel.WHISPER_1,
        responseFormat: TranslationCreateParams.ResponseFormat? = null,
    ): TranslationCreateParams {
        val builder = TranslationCreateParams.builder()
            .file(audioFile())
            .model(model)
        if (responseFormat != null) {
            builder.responseFormat(responseFormat)
        }
        return builder.build()
    }

    private fun jsonTranslationResponse(): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"text": "Hello world."}""")
    }

    private fun verboseJsonTranslationResponse(
        duration: Double = 3.0,
        language: String = "english",
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "task": "translate",
                  "language": "$language",
                  "duration": $duration,
                  "text": "Hello world."
                }
                """.trimIndent()
            )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
        private const val AUDIO_FILE = "lofi.wav"
        private const val AUDIO_CONTENT_TYPE = "audio/wav"
    }
}
