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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [AudioOpenAIApiEndpointHandler] using [MockWebServer].
 *
 * No real network calls or API keys are required.
 */
@Tag("openai")
class AudioOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `audio transcription sets api type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueueVerboseResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/transcriptions"))
                    .post(buildMultipartBody("whisper-1", "verbose_json"))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("audio", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("audio.transcription", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `audio transcription extracts model and audio file attributes from multipart form`() = runTest {
        withMockServer { server ->
            server.enqueueVerboseResponse()

            val audioBytes = ByteArray(1024) { it.toByte() }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart(
                    "file", "audio.mp3",
                    audioBytes.toRequestBody("audio/mpeg".toMediaType())
                )
                .addFormDataPart("response_format", "verbose_json")
                .build()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/transcriptions"))
                    .post(body)
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("whisper-1", trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertEquals(1024L, trace.attributes[AttributeKey.longKey("tracy.request.audio.size_bytes")])
            assertEquals("mp3", trace.attributes[AttributeKey.stringKey("tracy.request.audio.format")])
            assertEquals("verbose_json", trace.attributes[AttributeKey.stringKey("tracy.request.response_format")])
            assertEquals("json", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `audio transcription audio format falls back to content-type subtype when filename has no extension`() = runTest {
        withMockServer { server ->
            server.enqueueVerboseResponse()

            val audioBytes = ByteArray(256) { 0 }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart(
                    "file", "recording",
                    audioBytes.toRequestBody("audio/ogg".toMediaType())
                )
                .addFormDataPart("response_format", "json")
                .build()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/transcriptions"))
                    .post(body)
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("ogg", trace.attributes[AttributeKey.stringKey("tracy.request.audio.format")])
        }
    }

    @Test
    fun `audio transcription collects multiple timestamp granularities`() = runTest {
        withMockServer { server ->
            server.enqueueVerboseResponse()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart(
                    "file", "audio.mp3",
                    ByteArray(128).toRequestBody("audio/mpeg".toMediaType())
                )
                .addFormDataPart("timestamp_granularities[]", "word")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .build()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/transcriptions"))
                    .post(body)
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("word,segment", trace.attributes[AttributeKey.stringKey("tracy.request.timestamp_granularities")])
        }
    }

    @Test
    fun `audio transcription response format text sets output type to text`() = runTest {
        withMockServer { server ->
            server.enqueueSimpleResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/transcriptions"))
                    .post(buildMultipartBody("whisper-1", "text"))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("text", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `audio transcription extracts duration language and word count from verbose json response`() = runTest {
        withMockServer { server ->
            server.enqueueVerboseResponse(duration = 12.5, language = "english", wordCount = 3)

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/audio/transcriptions"))
                    .post(buildMultipartBody("whisper-1", "verbose_json"))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals(12.5, trace.attributes[AttributeKey.doubleKey("tracy.response.transcription.duration_seconds")])
            assertEquals("english", trace.attributes[AttributeKey.stringKey("tracy.response.transcription.language")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("tracy.response.transcription.words.count")])
        }
    }

    // ===== Helpers =====

    private fun buildClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter())

    private fun buildMultipartBody(model: String, responseFormat: String): RequestBody {
        return MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart(
                "file", "test.mp3",
                ByteArray(512).toRequestBody("audio/mpeg".toMediaType())
            )
            .addFormDataPart("response_format", responseFormat)
            .build()
    }

    private fun MockWebServer.enqueueVerboseResponse(
        duration: Double = 5.0,
        language: String = "english",
        wordCount: Int = 2,
    ) {
        val words = (1..wordCount).joinToString(",") { i ->
            """{"word":"word$i","start":${i - 1}.0,"end":${i}.0}"""
        }
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"task":"transcribe","language":"$language","duration":$duration,"text":"Hello world","words":[$words]}"""
                )
        )
    }

    private fun MockWebServer.enqueueSimpleResponse() {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text":"Hello world"}""")
        )
    }
}
