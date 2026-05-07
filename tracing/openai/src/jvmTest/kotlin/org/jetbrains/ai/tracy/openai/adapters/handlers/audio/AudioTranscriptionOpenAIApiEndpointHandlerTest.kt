/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import com.openai.core.MultipartField
import com.openai.models.audio.AudioModel
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.policy.ContentCapturePolicy
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.InputStream
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [AudioTranscriptionOpenAIApiEndpointHandler].
 *
 * These tests use [MockWebServer] and a mock OpenAI API key and do not require
 * access to the real OpenAI Audio API.
 */
@Tag("openai")
class AudioTranscriptionOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `test basic audio transcription request and response are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            val transcribedText = "Hello, how are you doing today?"
            val model = AudioModel.WHISPER_1
            val audioFile = "lofi.wav"

            server.enqueueTranscriptionResponse(
                text = transcribedText,
                task = "transcribe",
                language = "english",
                duration = 5.0,
            )

            val params = TranscriptionCreateParams.builder()
                .file(
                    MultipartField.builder<InputStream>()
                        .value(readResource(audioFile))
                        .contentType("audio/wav")
                        .filename(audioFile)
                        .build()
                )
                .model(model)
                .build()

            client.audio().transcriptions().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Request attributes
            assertEquals(model.asString(), trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])

            // Response attributes
            assertEquals(
                transcribedText,
                trace.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")]
            )
            assertEquals("transcribe", trace.attributes[AttributeKey.stringKey("gen_ai.response.task")])
            assertEquals("english", trace.attributes[AttributeKey.stringKey("gen_ai.response.language")])
        }
    }

    @Test
    fun `test audio file is traced in request when content tracing is allowed`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            val audioFile = "lofi.wav"
            val contentType = "audio/wav"

            server.enqueueTranscriptionResponse(text = "Some transcription")

            val params = TranscriptionCreateParams.builder()
                .file(
                    MultipartField.builder<InputStream>()
                        .value(readResource(audioFile))
                        .contentType(contentType)
                        .filename(audioFile)
                        .build()
                )
                .model(AudioModel.WHISPER_1)
                .build()

            client.audio().transcriptions().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Audio content attributes should be set when input tracing is allowed
            assertFalse(trace.attributes[AttributeKey.stringKey("gen_ai.request.audio.content")].isNullOrEmpty())
            assertEquals(contentType, trace.attributes[AttributeKey.stringKey("gen_ai.request.audio.content_type")])
            assertEquals(audioFile, trace.attributes[AttributeKey.stringKey("gen_ai.request.audio.filename")])
        }
    }

    @Test
    fun `test optional request fields are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            val language = "en"
            val audioFile = "lofi.wav"

            server.enqueueTranscriptionResponse(text = "Some transcription")

            val params = TranscriptionCreateParams.builder()
                .file(
                    MultipartField.builder<InputStream>()
                        .value(readResource(audioFile))
                        .contentType("audio/wav")
                        .filename(audioFile)
                        .build()
                )
                .model(AudioModel.WHISPER_1)
                .language(language)
                .build()

            client.audio().transcriptions().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(language, trace.attributes[AttributeKey.stringKey("gen_ai.request.language")])
        }
    }

    @ParameterizedTest
    @MethodSource("provideContentCapturePolicies")
    fun `test capture policy controls audio content and transcription visibility`(policy: ContentCapturePolicy) =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                TracingManager.withCapturingPolicy(policy)

                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(1)
                ).apply { instrument(this) }

                val transcribedText = "Hello world transcription"
                val audioFile = "lofi.wav"

                server.enqueueTranscriptionResponse(text = transcribedText)

                val params = TranscriptionCreateParams.builder()
                    .file(
                        MultipartField.builder<InputStream>()
                            .value(readResource(audioFile))
                            .contentType("audio/wav")
                            .filename(audioFile)
                            .build()
                    )
                    .model(AudioModel.WHISPER_1)
                    .build()

                client.audio().transcriptions().create(params)

                val traces = analyzeSpans()
                assertTracesCount(1, traces)
                val trace = traces.first()

                // Audio content upload attributes are gated on input capture policy
                val audioContent = trace.attributes[AttributeKey.stringKey("gen_ai.request.audio.content")]
                if (!policy.captureInputs) {
                    assertTrue(audioContent.isNullOrEmpty(), "Audio content should NOT be traced when input capture is disabled")
                } else {
                    assertFalse(audioContent.isNullOrEmpty(), "Audio content should be traced when input capture is enabled")
                }

                // Transcription text is gated on output capture policy
                val completionContent = trace.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")]
                if (!policy.captureOutputs) {
                    assertEquals("REDACTED", completionContent, "Transcription text should be redacted when output capture is disabled")
                } else {
                    assertNotEquals("REDACTED", completionContent, "Transcription text should NOT be redacted when output capture is enabled")
                }
            }
        }

    // ============ HELPER METHODS ============

    private fun MockWebServer.enqueueTranscriptionResponse(
        text: String,
        task: String = "transcribe",
        language: String = "english",
        duration: Double = 3.0,
    ) {
        this.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "text": "$text",
                        "task": "$task",
                        "language": "$language",
                        "duration": $duration
                    }
                    """.trimIndent()
                )
        )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
