/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import com.openai.core.MultipartField
import com.openai.models.audio.AudioModel
import com.openai.models.audio.AudioResponseFormat
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import com.openai.models.audio.transcriptions.TranscriptionInclude
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
class AudioTranscriptionOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `test operation name and api type are set for audio transcription`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranscriptionResponse())

            val params = transcriptionParams(AudioResponseFormat.JSON)
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals("audio.transcription", trace.attributes[GEN_AI_OPERATION_NAME])
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

            server.enqueue(jsonTranscriptionResponse())

            val model = AudioModel.WHISPER_1
            val params = transcriptionParams(AudioResponseFormat.JSON, model = model)
            client.audio().transcriptions().create(params)

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

            server.enqueue(jsonTranscriptionResponse())

            val params = transcriptionParams(AudioResponseFormat.JSON)
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            // Audio file size should be traced
            val sizeBytes = trace.attributes[AttributeKey.longKey("tracy.request.audio.size_bytes")]
            assertNotNull(sizeBytes, "Audio size_bytes should be traced")
            assertTrue(sizeBytes!! > 0, "Audio size_bytes should be positive")

            // Audio MIME type should be traced (audio/wav from lofi.wav)
            assertEquals(
                AUDIO_CONTENT_TYPE,
                trace.attributes[AttributeKey.stringKey("tracy.request.audio.mime_type")]
            )
        }
    }

    @Test
    fun `test language is traced from request form data`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranscriptionResponse())

            val params = TranscriptionCreateParams.builder()
                .file(audioFile())
                .model(AudioModel.WHISPER_1)
                .language("en")
                .responseFormat(AudioResponseFormat.JSON)
                .build()
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals("en", trace.attributes[AttributeKey.stringKey("tracy.request.language")])
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

            server.enqueue(jsonTranscriptionResponse())

            val params = TranscriptionCreateParams.builder()
                .file(audioFile())
                .model(AudioModel.WHISPER_1)
                .temperature(0.5)
                .responseFormat(AudioResponseFormat.JSON)
                .build()
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals(0.5, trace.attributes[AttributeKey.doubleKey("tracy.request.temperature")])
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

            server.enqueue(jsonTranscriptionResponse())

            val params = TranscriptionCreateParams.builder()
                .file(audioFile())
                .model(AudioModel.WHISPER_1)
                .prompt("This is a meeting transcript")
                .responseFormat(AudioResponseFormat.JSON)
                .build()
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals("REDACTED", trace.attributes[AttributeKey.stringKey("tracy.request.prompt")])
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

            server.enqueue(jsonTranscriptionResponse())

            val params = TranscriptionCreateParams.builder()
                .file(audioFile())
                .model(AudioModel.WHISPER_1)
                .prompt("This is a meeting transcript")
                .responseFormat(AudioResponseFormat.JSON)
                .build()
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                "This is a meeting transcript",
                trace.attributes[AttributeKey.stringKey("tracy.request.prompt")]
            )
        }
    }

    @Test
    fun `test include is traced from request form data`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranscriptionResponse())

            val params = TranscriptionCreateParams.builder()
                .file(audioFile())
                .model(AudioModel.WHISPER_1)
                .include(listOf(TranscriptionInclude.LOGPROBS))
                .responseFormat(AudioResponseFormat.JSON)
                .build()
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            val include = trace.attributes[AttributeKey.stringKey("tracy.request.include")]
            assertNotNull(include, "Include should be traced")
            assertTrue(include!!.contains("logprobs"), "Include should contain 'logprobs'")
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

            server.enqueue(jsonTranscriptionResponse())

            val params = transcriptionParams(AudioResponseFormat.JSON)
            client.audio().transcriptions().create(params)

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

            server.enqueue(verboseJsonTranscriptionResponse())

            val params = transcriptionParams(AudioResponseFormat.VERBOSE_JSON)
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals("verbose_json", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `test verbose json response attributes are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            TracingManager.withCapturingPolicy(
                ContentCapturePolicy(captureInputs = true, captureOutputs = true)
            )

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(verboseJsonTranscriptionResponse(
                duration = 5.5,
                language = "english",
                wordsCount = 3,
            ))

            val params = transcriptionParams(AudioResponseFormat.VERBOSE_JSON)
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                "english",
                trace.attributes[AttributeKey.stringKey("tracy.response.transcription.language")]
            )
            assertNotNull(
                trace.attributes[AttributeKey.doubleKey("tracy.response.transcription.duration_seconds")],
                "Duration should be traced"
            )
            assertEquals(
                3L,
                trace.attributes[AttributeKey.longKey("tracy.response.transcription.words.count")]
            )
            assertEquals(
                "Hello world.",
                trace.attributes[AttributeKey.stringKey("tracy.response.transcription.text")]
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

            server.enqueue(jsonTranscriptionResponse())

            val params = transcriptionParams(AudioResponseFormat.JSON)
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                "REDACTED",
                trace.attributes[AttributeKey.stringKey("tracy.response.transcription.text")]
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

            server.enqueue(jsonTranscriptionResponse())

            val params = transcriptionParams(AudioResponseFormat.JSON)
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                "Hello world.",
                trace.attributes[AttributeKey.stringKey("tracy.response.transcription.text")]
            )
        }
    }

    @Test
    fun `test segments count is traced from response body`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
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
                          "task": "transcribe",
                          "language": "english",
                          "duration": 5.0,
                          "text": "Hello world.",
                          "segments": [
                            {"id": 0, "start": 0.0, "end": 2.5, "text": "Hello"},
                            {"id": 1, "start": 2.5, "end": 5.0, "text": "world."}
                          ]
                        }
                        """.trimIndent()
                    )
            )

            val params = transcriptionParams(AudioResponseFormat.VERBOSE_JSON)
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                2L,
                trace.attributes[AttributeKey.longKey("tracy.response.transcription.segments.count")]
            )
            assertEquals(
                "transcribe",
                trace.attributes[AttributeKey.stringKey("tracy.response.transcription.task")]
            )
        }
    }

    @Test
    fun `test token usage is traced from response body`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
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
                          "text": "Hello world.",
                          "usage": {
                            "type": "tokens",
                            "input_tokens": 12,
                            "output_tokens": 4,
                            "total_tokens": 16
                          }
                        }
                        """.trimIndent()
                    )
            )

            val params = transcriptionParams(AudioResponseFormat.JSON)
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals("tokens", trace.attributes[AttributeKey.stringKey("gen_ai.usage.type")])
            assertEquals(12L, trace.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
            assertEquals(4L, trace.attributes[AttributeKey.longKey("gen_ai.usage.output_tokens")])
            assertEquals(16L, trace.attributes[AttributeKey.longKey("gen_ai.usage.total_tokens")])
        }
    }

    @Test
    fun `test duration usage is traced from response body`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
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
                          "text": "Hello world.",
                          "usage": {
                            "type": "duration",
                            "seconds": 7.25
                          }
                        }
                        """.trimIndent()
                    )
            )

            val params = transcriptionParams(AudioResponseFormat.JSON)
            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            assertEquals("duration", trace.attributes[AttributeKey.stringKey("gen_ai.usage.type")])
            assertEquals(7.25, trace.attributes[AttributeKey.doubleKey("gen_ai.usage.seconds")])
        }
    }

    @Test
    fun `test timestamp granularities are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(verboseJsonTranscriptionResponse())

            val params = TranscriptionCreateParams.builder()
                .file(audioFile())
                .model(AudioModel.WHISPER_1)
                .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                .addTimestampGranularity(TranscriptionCreateParams.TimestampGranularity.WORD)
                .build()

            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            val granularities = trace.attributes[AttributeKey.stringKey("tracy.request.timestamp_granularities")]
            assertNotNull(granularities, "Timestamp granularities should be traced")
            assertTrue(
                granularities!!.contains("word"),
                "Granularities should contain 'word'"
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

    private fun transcriptionParams(
        responseFormat: AudioResponseFormat,
        model: AudioModel = AudioModel.WHISPER_1,
    ): TranscriptionCreateParams {
        return TranscriptionCreateParams.builder()
            .file(audioFile())
            .model(model)
            .responseFormat(responseFormat)
            .build()
    }

    private fun jsonTranscriptionResponse(): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"text": "Hello world."}""")
    }

    private fun verboseJsonTranscriptionResponse(
        duration: Double = 3.0,
        language: String = "english",
        wordsCount: Int = 2,
    ): MockResponse {
        val wordsJson = (1..wordsCount).joinToString(",") { i ->
            """{"word": "word$i", "start": ${(i - 1) * 0.5}, "end": ${i * 0.5}}"""
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "task": "transcribe",
                  "language": "$language",
                  "duration": $duration,
                  "text": "Hello world.",
                  "words": [$wordsJson]
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
