/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import com.openai.core.MultipartField
import com.openai.models.audio.AudioModel
import com.openai.models.audio.AudioResponseFormat
import com.openai.models.audio.speech.SpeechCreateParams
import com.openai.models.audio.speech.SpeechModel
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import com.openai.models.audio.translations.TranslationCreateParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [AudioOpenAIApiEndpointHandler].
 *
 * These tests use [MockWebServer] and a mock OpenAI API key, so they do not require access
 * to the real OpenAI Audio API.
 */
@Tag("openai")
class AudioOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ openai.api.type ============

    @Test
    fun `test openai api type is always set to audio for transcription`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueTranscriptionResponse(text = "Hello world")

            client.audio().transcriptions().create(
                TranscriptionCreateParams.builder()
                    .file(dummyAudioFile())
                    .model(AudioModel.WHISPER_1)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test openai api type is always set to audio for speech`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueAudioBinaryResponse()

            client.audio().speech().create(
                SpeechCreateParams.builder()
                    .input("Hello world")
                    .model(SpeechModel.TTS_1)
                    .voice(SpeechCreateParams.Voice.ALLOY)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    // ============ TRANSCRIPTION: POST /v1/audio/transcriptions ============

    @Test
    fun `test transcription operation name and model are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueTranscriptionResponse(text = "Hello world")

            client.audio().transcriptions().create(
                TranscriptionCreateParams.builder()
                    .file(dummyAudioFile())
                    .model(AudioModel.WHISPER_1)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("audio.transcription", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(AudioModel.WHISPER_1.asString(), trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    @Test
    fun `test transcription audio file attributes use tracy namespace`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueTranscriptionResponse(text = "Hello world")

            val audioBytes = ByteArray(512) { it.toByte() }
            client.audio().transcriptions().create(
                TranscriptionCreateParams.builder()
                    .file(
                        MultipartField.builder<InputStream>()
                            .value(audioBytes.inputStream())
                            .filename("test.mp3")
                            .contentType("audio/mpeg")
                            .build()
                    )
                    .model(AudioModel.WHISPER_1)
                    .build()
            )

            val trace = analyzeSpans().first()
            // Must use tracy.* namespace, not gen_ai.*
            assertEquals(512L, trace.attributes[AttributeKey.longKey("tracy.request.audio.size_bytes")])
            assertEquals("mp3", trace.attributes[AttributeKey.stringKey("tracy.request.audio.format")])
            // Old gen_ai.* keys must NOT be set
            assertNull(trace.attributes[AttributeKey.longKey("gen_ai.request.audio.size_bytes")])
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.audio.format")])
        }
    }

    @Test
    fun `test response format uses tracy namespace for transcription`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueTranscriptionResponse(text = "Hello world")

            client.audio().transcriptions().create(
                TranscriptionCreateParams.builder()
                    .file(dummyAudioFile())
                    .model(AudioModel.WHISPER_1)
                    .responseFormat(AudioResponseFormat.JSON)
                    .build()
            )

            val trace = analyzeSpans().first()
            // Must use tracy.* namespace, not gen_ai.*
            assertEquals("json", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.response_format")])
        }
    }

    @Test
    fun `test gen_ai output type is json for json response format`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueTranscriptionResponse(text = "Hello world")

            client.audio().transcriptions().create(
                TranscriptionCreateParams.builder()
                    .file(dummyAudioFile())
                    .model(AudioModel.WHISPER_1)
                    .responseFormat(AudioResponseFormat.JSON)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `test gen_ai output type is json for verbose_json response format`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueVerboseJsonTranscriptionResponse()

            client.audio().transcriptions().create(
                TranscriptionCreateParams.builder()
                    .file(dummyAudioFile())
                    .model(AudioModel.WHISPER_1)
                    .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `test gen_ai output type is not set for non-json response formats`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            // text format returns plain text, not JSON
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/plain")
                    .setBody("Hello world")
            )

            client.audio().transcriptions().create(
                TranscriptionCreateParams.builder()
                    .file(dummyAudioFile())
                    .model(AudioModel.WHISPER_1)
                    .responseFormat(AudioResponseFormat.TEXT)
                    .build()
            )

            val trace = analyzeSpans().first()
            // output type should NOT be set for non-json formats
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `test timestamp granularities are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueVerboseJsonTranscriptionResponse()

            client.audio().transcriptions().create(
                TranscriptionCreateParams.builder()
                    .file(dummyAudioFile())
                    .model(AudioModel.WHISPER_1)
                    .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                    .addTimestampGranularity(TranscriptionCreateParams.TimestampGranularity.WORD)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.request.timestamp_granularities")])
            val granularities = trace.attributes[AttributeKey.stringKey("tracy.request.timestamp_granularities")]!!
            assert(granularities.contains("word")) { "Expected 'word' in timestamp_granularities, got: $granularities" }
        }
    }

    @Test
    fun `test verbose json response fields are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueVerboseJsonTranscriptionResponse(
                text = "Hello world",
                language = "english",
                duration = 2.5,
                words = listOf("Hello", "world")
            )

            client.audio().transcriptions().create(
                TranscriptionCreateParams.builder()
                    .file(dummyAudioFile())
                    .model(AudioModel.WHISPER_1)
                    .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                    .build()
            )

            val trace = analyzeSpans().first()

            // verify verbose_json specific fields
            assertEquals(2.5, trace.attributes[AttributeKey.doubleKey("tracy.response.transcription.duration_seconds")])
            assertEquals("english", trace.attributes[AttributeKey.stringKey("tracy.response.transcription.language")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.response.transcription.words.count")])

            // basic text is still traced
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.text")])
        }
    }

    @Test
    fun `test simple json response does not set verbose fields`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueTranscriptionResponse(text = "Hello world")

            client.audio().transcriptions().create(
                TranscriptionCreateParams.builder()
                    .file(dummyAudioFile())
                    .model(AudioModel.WHISPER_1)
                    .responseFormat(AudioResponseFormat.JSON)
                    .build()
            )

            val trace = analyzeSpans().first()

            // verbose fields should not be set for simple json response
            assertNull(trace.attributes[AttributeKey.doubleKey("tracy.response.transcription.duration_seconds")])
            assertNull(trace.attributes[AttributeKey.stringKey("tracy.response.transcription.language")])
            assertNull(trace.attributes[AttributeKey.longKey("tracy.response.transcription.words.count")])
        }
    }

    // ============ TRANSLATION: POST /v1/audio/translations ============

    @Test
    fun `test translation operation name and output type are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueTranscriptionResponse(text = "Hello world")

            client.audio().translations().create(
                TranslationCreateParams.builder()
                    .file(dummyAudioFile())
                    .model(AudioModel.WHISPER_1)
                    .responseFormat(TranslationCreateParams.ResponseFormat.JSON)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("audio.translation", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    // ============ SPEECH: POST /v1/audio/speech ============

    @Test
    fun `test speech operation name and output type are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueAudioBinaryResponse()

            client.audio().speech().create(
                SpeechCreateParams.builder()
                    .input("Hello world")
                    .model(SpeechModel.TTS_1)
                    .voice(SpeechCreateParams.Voice.ALLOY)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("audio.speech", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("speech", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `test speech request attributes are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueAudioBinaryResponse()

            val inputText = "Hello world"
            client.audio().speech().create(
                SpeechCreateParams.builder()
                    .input(inputText)
                    .model(SpeechModel.TTS_1)
                    .voice(SpeechCreateParams.Voice.ALLOY)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.input")])
            assertEquals("alloy", trace.attributes[AttributeKey.stringKey("gen_ai.request.voice")])
        }
    }

    @Test
    fun `test speech response size is traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val audioBytes = ByteArray(2048) { 0 }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setHeader("Content-Length", audioBytes.size.toString())
                    .setBody(okio.Buffer().write(audioBytes))
            )

            client.audio().speech().create(
                SpeechCreateParams.builder()
                    .input("Hello world")
                    .model(SpeechModel.TTS_1)
                    .voice(SpeechCreateParams.Voice.ALLOY)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(2048L, trace.attributes[AttributeKey.longKey("gen_ai.response.audio.size_bytes")])
        }
    }

    // ============ HELPER METHODS ============

    private fun dummyAudioFile(): MultipartField<InputStream> {
        val bytes = ByteArray(256) { it.toByte() }
        return MultipartField.builder<InputStream>()
            .value(bytes.inputStream())
            .filename("test.mp3")
            .contentType("audio/mpeg")
            .build()
    }

    private fun MockWebServer.enqueueTranscriptionResponse(text: String) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text":"$text"}""")
        )
    }

    private fun MockWebServer.enqueueVerboseJsonTranscriptionResponse(
        text: String = "Hello world",
        language: String = "english",
        duration: Double = 2.5,
        words: List<String> = listOf("Hello", "world"),
    ) {
        val wordsJson = words.joinToString(",") { word ->
            """{"word":"$word","start":0.0,"end":0.5}"""
        }
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "task": "transcribe",
                        "language": "$language",
                        "duration": $duration,
                        "text": "$text",
                        "words": [$wordsJson],
                        "segments": []
                    }
                    """.trimIndent()
                )
        )
    }

    private fun MockWebServer.enqueueAudioBinaryResponse() {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(okio.Buffer().write(ByteArray(512) { 0 }))
        )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
