/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

import com.openai.core.MultipartField
import com.openai.models.audio.AudioModel
import com.openai.models.audio.translations.TranslationCreateParams
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

            assertEquals("audio.translation", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
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

            val format = trace.attributes[AttributeKey.stringKey("tracy.request.audio.format")]
            assertNotNull(format, "Audio format should be traced")
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
    fun `test verbose json response duration is traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(verboseJsonTranslationResponse(duration = 7.5))

            val params = translationParams(responseFormat = TranslationCreateParams.ResponseFormat.VERBOSE_JSON)
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertNotNull(
                trace.attributes[AttributeKey.doubleKey("tracy.response.translation.duration_seconds")],
                "Duration should be traced"
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
                .file(
                    MultipartField.builder<InputStream>()
                        .value(readResource(AUDIO_FILE))
                        .contentType(AUDIO_CONTENT_TYPE)
                        .filename(AUDIO_FILE)
                        .build()
                )
                .model(AudioModel.WHISPER_1)
                .temperature(0.5)
                .build()
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertNotNull(
                trace.attributes[AttributeKey.doubleKey("gen_ai.request.temperature")],
                "Temperature should be traced"
            )
        }
    }

    @Test
    fun `test prompt presence is traced from request form data`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(jsonTranslationResponse())

            val params = TranslationCreateParams.builder()
                .file(
                    MultipartField.builder<InputStream>()
                        .value(readResource(AUDIO_FILE))
                        .contentType(AUDIO_CONTENT_TYPE)
                        .filename(AUDIO_FILE)
                        .build()
                )
                .model(AudioModel.WHISPER_1)
                .prompt("Translate the following audio to English.")
                .build()
            client.audio().translations().create(params)

            val trace = analyzeSpans().first()

            assertEquals(
                true,
                trace.attributes[AttributeKey.booleanKey("tracy.request.prompt.present")],
                "Prompt present should be traced as true"
            )
        }
    }

    // ============ HELPER METHODS ============

    private fun translationParams(
        model: AudioModel = AudioModel.WHISPER_1,
        responseFormat: TranslationCreateParams.ResponseFormat? = null,
    ): TranslationCreateParams {
        val builder = TranslationCreateParams.builder()
            .file(
                MultipartField.builder<InputStream>()
                    .value(readResource(AUDIO_FILE))
                    .contentType(AUDIO_CONTENT_TYPE)
                    .filename(AUDIO_FILE)
                    .build()
            )
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
