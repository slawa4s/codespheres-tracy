/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import com.openai.core.MultipartField
import com.openai.models.audio.AudioModel
import com.openai.models.audio.AudioResponseFormat
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.jetbrains.ai.tracy.test.utils.loadFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [AudioTranscriptionOpenAIApiEndpointHandler].
 *
 * These tests use [MockWebServer] and a mock OpenAI API key, so they do not require access
 * to the real OpenAI Audio API.
 */
@Tag("openai")
class AudioTranscriptionOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ REQUEST ATTRIBUTE EXTRACTION ============

    @Test
    fun `test basic request attributes are traced for audio transcription`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val model = AudioModel.WHISPER_1
                val audioFile = loadFile("lofi.wav")

                server.enqueueVerboseTranscriptionResponse()

                val params = TranscriptionCreateParams.builder()
                    .file(
                        MultipartField.builder<java.io.InputStream>()
                            .value(audioFile.inputStream())
                            .contentType("audio/wav")
                            .filename("lofi.wav")
                            .build()
                    )
                    .model(model)
                    .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                    .build()

                client.audio().transcriptions().create(params)

                val traces = analyzeSpans()
                assertTracesCount(1, traces)
                val trace = traces.first()

                // Verify operation type attributes
                assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
                assertEquals(
                    "audio.transcription",
                    trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
                )

                // Verify model is traced
                assertEquals(
                    model.asString(),
                    trace.attributes[AttributeKey.stringKey("gen_ai.request.model")]
                )

                // Verify response_format is traced and output type derived
                assertEquals(
                    "verbose_json",
                    trace.attributes[AttributeKey.stringKey("tracy.request.response_format")]
                )
                assertEquals(
                    "json",
                    trace.attributes[AttributeKey.stringKey("gen_ai.output.type")]
                )

                // Verify audio file size is traced
                assertNotNull(trace.attributes[AttributeKey.longKey("tracy.request.audio.size_bytes")])

                // Verify audio format is traced from content type
                assertEquals("wav", trace.attributes[AttributeKey.stringKey("tracy.request.audio.format")])
            }
        }

    @Test
    fun `test timestamp_granularities are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val audioFile = loadFile("lofi.wav")

            server.enqueueVerboseTranscriptionResponse()

            val params = TranscriptionCreateParams.builder()
                .file(
                    MultipartField.builder<java.io.InputStream>()
                        .value(audioFile.inputStream())
                        .contentType("audio/wav")
                        .filename("lofi.wav")
                        .build()
                )
                .model(AudioModel.WHISPER_1)
                .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                .addTimestampGranularity(TranscriptionCreateParams.TimestampGranularity.WORD)
                .addTimestampGranularity(TranscriptionCreateParams.TimestampGranularity.SEGMENT)
                .build()

            client.audio().transcriptions().create(params)

            val trace = analyzeSpans().first()

            // Timestamp granularities should be traced as comma-separated string
            val granularities = trace.attributes[AttributeKey.stringKey("tracy.request.timestamp_granularities")]
            assertNotNull(granularities, "timestamp_granularities should be traced")
            // Should contain both word and segment
            assert(granularities!!.contains("word")) { "Should contain 'word' granularity" }
            assert(granularities.contains("segment")) { "Should contain 'segment' granularity" }
        }
    }

    // ============ RESPONSE ATTRIBUTE EXTRACTION ============

    @Test
    fun `test response attributes are extracted from verbose_json transcription`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val audioFile = loadFile("lofi.wav")
                val expectedDuration = 12.5
                val expectedLanguage = "english"
                val expectedWordsCount = 3

                server.enqueueVerboseTranscriptionResponse(
                    duration = expectedDuration,
                    language = expectedLanguage,
                    words = listOf("hello", "world", "test"),
                )

                val params = TranscriptionCreateParams.builder()
                    .file(audioFile.inputStream())
                    .model(AudioModel.WHISPER_1)
                    .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                    .build()

                client.audio().transcriptions().create(params)

                val trace = analyzeSpans().first()

                // Verify response attributes
                assertEquals(
                    expectedDuration,
                    trace.attributes[AttributeKey.doubleKey("tracy.response.transcription.duration_seconds")]
                )
                assertEquals(
                    expectedLanguage,
                    trace.attributes[AttributeKey.stringKey("tracy.response.transcription.language")]
                )
                assertEquals(
                    expectedWordsCount.toLong(),
                    trace.attributes[AttributeKey.longKey("tracy.response.transcription.words.count")]
                )
            }
        }

    // ============ OUTPUT TYPE DERIVATION ============

    @Test
    fun `test gen_ai_output_type is json when response_format is json`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val audioFile = loadFile("lofi.wav")

                server.enqueue(MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"text":"Hello world"}"""))

                val params = TranscriptionCreateParams.builder()
                    .file(audioFile.inputStream())
                    .model(AudioModel.WHISPER_1)
                    .responseFormat(AudioResponseFormat.JSON)
                    .build()

                client.audio().transcriptions().create(params)

                val trace = analyzeSpans().first()

                assertEquals("json", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
                assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            }
        }

    // ============ AUDIO FORMAT DETECTION ============

    @Test
    fun `test audio format is detected from filename when content type is missing`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val audioFile = loadFile("lofi.wav")

                server.enqueueVerboseTranscriptionResponse()

                // Use MultipartField with a filename but no explicit content type header
                // (content type will be inferred)
                val params = TranscriptionCreateParams.builder()
                    .file(
                        MultipartField.builder<java.io.InputStream>()
                            .value(audioFile.inputStream())
                            .filename("recording.mp3")
                            .build()
                    )
                    .model(AudioModel.WHISPER_1)
                    .build()

                client.audio().transcriptions().create(params)

                val trace = analyzeSpans().first()

                // Format should be derived from filename extension
                val format = trace.attributes[AttributeKey.stringKey("tracy.request.audio.format")]
                assertNotNull(format, "Audio format should be traced")
            }
        }

    // ============ HELPER METHODS ============

    private fun MockWebServer.enqueueVerboseTranscriptionResponse(
        text: String = "Hello world",
        language: String = "english",
        duration: Double = 5.0,
        words: List<String> = listOf("Hello", "world"),
    ) {
        val wordsJson = words.joinToString(",") { word ->
            """{"word":"$word","start":0.0,"end":0.5}"""
        }
        val segmentsJson = """[{"id":0,"seek":0,"start":0.0,"end":$duration,"text":"$text","tokens":[],"temperature":0.0,"avg_logprob":0.0,"compression_ratio":1.0,"no_speech_prob":0.0}]"""

        this.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "task": "transcribe",
                    "language": "$language",
                    "duration": $duration,
                    "text": "$text",
                    "words": [$wordsJson],
                    "segments": $segmentsJson
                }
            """.trimIndent()))
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
