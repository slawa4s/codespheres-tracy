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
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

@Tag("openai")
class AudioTranscriptionOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `test basic audio transcription attributes are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val model = AudioModel.WHISPER_1
            val audioFile = "lofi.wav"
            val audioBytes = readResource(audioFile).readBytes()

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "task": "transcribe",
                      "language": "english",
                      "duration": 8.470000267028809,
                      "text": "The quick brown fox.",
                      "words": [
                        {"word": "The", "start": 0.0, "end": 0.24},
                        {"word": "quick", "start": 0.26, "end": 0.5}
                      ],
                      "segments": []
                    }
                """.trimIndent())
            )

            val params = TranscriptionCreateParams.builder()
                .file(
                    MultipartField.builder<InputStream>()
                        .value(audioBytes.inputStream())
                        .contentType("audio/wav")
                        .filename(audioFile)
                        .build()
                )
                .model(model)
                .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                .build()

            client.audio().transcriptions().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("audio.transcription", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(model.asString(), trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertEquals("verbose_json", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
            assertEquals(audioBytes.size.toLong(), trace.attributes[AttributeKey.longKey("tracy.request.audio.size_bytes")])
            assertEquals("wav", trace.attributes[AttributeKey.stringKey("tracy.request.audio.format")])

            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals(8.47, trace.attributes[AttributeKey.doubleKey("tracy.response.transcription.duration_seconds")]!!, 0.01)
            assertEquals("english", trace.attributes[AttributeKey.stringKey("tracy.response.transcription.language")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.response.transcription.words.count")])
        }
    }

    @Test
    fun `test audio format is detected from content type`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text": "Hello world."}""")
            )

            val params = TranscriptionCreateParams.builder()
                .file(
                    MultipartField.builder<InputStream>()
                        .value(byteArrayOf(0x00, 0x01, 0x02).inputStream())
                        .contentType("audio/mp3")
                        .filename("recording.mp3")
                        .build()
                )
                .model(AudioModel.WHISPER_1)
                .responseFormat(AudioResponseFormat.JSON)
                .build()

            client.audio().transcriptions().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("mp3", trace.attributes[AttributeKey.stringKey("tracy.request.audio.format")])
            assertEquals("json", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `test timestamp granularities are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "task": "transcribe",
                      "language": "english",
                      "duration": 3.5,
                      "text": "Hello.",
                      "words": [{"word": "Hello", "start": 0.0, "end": 0.5}],
                      "segments": []
                    }
                """.trimIndent())
            )

            val params = TranscriptionCreateParams.builder()
                .file(
                    MultipartField.builder<InputStream>()
                        .value(byteArrayOf(0x00, 0x01, 0x02).inputStream())
                        .contentType("audio/wav")
                        .filename("test.wav")
                        .build()
                )
                .model(AudioModel.WHISPER_1)
                .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                .addTimestampGranularity(TranscriptionCreateParams.TimestampGranularity.WORD)
                .addTimestampGranularity(TranscriptionCreateParams.TimestampGranularity.SEGMENT)
                .build()

            client.audio().transcriptions().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            val granularities = trace.attributes[AttributeKey.stringKey("tracy.request.timestamp_granularities")]
            assertNotNull(granularities, "timestamp_granularities should be traced")
            assertTrue(granularities!!.contains("word"), "Should contain 'word' granularity")
            assertTrue(granularities.contains("segment"), "Should contain 'segment' granularity")
        }
    }

    @Test
    fun `test audio size bytes are traced from file content`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val audioData = ByteArray(1024) { it.toByte() }

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text": "Test transcription."}""")
            )

            val params = TranscriptionCreateParams.builder()
                .file(
                    MultipartField.builder<InputStream>()
                        .value(audioData.inputStream())
                        .contentType("audio/flac")
                        .filename("audio.flac")
                        .build()
                )
                .model(AudioModel.WHISPER_1)
                .build()

            client.audio().transcriptions().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(1024L, trace.attributes[AttributeKey.longKey("tracy.request.audio.size_bytes")])
            assertEquals("flac", trace.attributes[AttributeKey.stringKey("tracy.request.audio.format")])
        }
    }

    @Test
    fun `test response without words array does not set words count`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text": "Hello world."}""")
            )

            val params = TranscriptionCreateParams.builder()
                .file(byteArrayOf(0x00, 0x01, 0x02))
                .model(AudioModel.WHISPER_1)
                .responseFormat(AudioResponseFormat.JSON)
                .build()

            client.audio().transcriptions().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertNull(trace.attributes[AttributeKey.longKey("tracy.response.transcription.words.count")])
            assertNull(trace.attributes[AttributeKey.doubleKey("tracy.response.transcription.duration_seconds")])
            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
