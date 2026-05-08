/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core

import io.opentelemetry.api.trace.Span
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [patchOpenAICompatibleClient] and [findFieldByType] resilience to
 * different SDK internal field naming conventions.
 */
class PatchOpenAICompatibleClientTest {

    private val noOpInterceptor = OpenTelemetryOkHttpInterceptor(
        adapter = object : LLMTracingAdapter("test") {
            override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {}
            override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {}
            override fun getSpanName(request: TracyHttpRequest) = "test"
            override fun isStreamingRequest(request: TracyHttpRequest) = false
            override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {}
        }
    )

    // ──────────────────────────────────────────────────────────────────────────────
    // Fake client structures used as stand-ins for different SDK versions
    // ──────────────────────────────────────────────────────────────────────────────

    /** Simulates the structure used by the standard Anthropic/OpenAI SDK:
     *  client.clientOptions.originalHttpClient.okHttpClient */
    private class FakeOkHttpWrapper(val okHttpClient: OkHttpClient)

    private class FakeClientOptions_OriginalHttpClient(val originalHttpClient: FakeOkHttpWrapper)

    private class FakeClient_Original(val clientOptions: FakeClientOptions_OriginalHttpClient)

    /** Simulates an SDK version where ClientOptions stores the HTTP client under "httpClient"
     *  instead of "originalHttpClient". */
    private class FakeClientOptions_HttpClient(val httpClient: FakeOkHttpWrapper)

    private class FakeClient_HttpClient(val clientOptions: FakeClientOptions_HttpClient)

    /** Simulates an SDK where the OkHttpClient field is renamed (e.g. due to @JvmSynthetic
     *  name mangling). The field holds an [OkHttpClient] but with a different field name. */
    private class FakeOkHttpWrapperRenamedField {
        @Suppress("unused")
        val renamedInternalHttpClient: OkHttpClient = OkHttpClient()
    }

    private class FakeClientOptions_RenamedField(
        val originalHttpClient: FakeOkHttpWrapperRenamedField
    )

    private class FakeClient_Renamed(val clientOptions: FakeClientOptions_RenamedField)

    // ──────────────────────────────────────────────────────────────────────────────
    // patchOpenAICompatibleClient tests
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `patchOpenAICompatibleClient succeeds with standard originalHttpClient field name`() {
        val realOkHttp = OkHttpClient()
        val client = FakeClient_Original(
            FakeClientOptions_OriginalHttpClient(FakeOkHttpWrapper(realOkHttp))
        )

        patchOpenAICompatibleClient(client, noOpInterceptor)

        assertEquals(1, realOkHttp.interceptors.size, "Interceptor should be injected into OkHttpClient")
        assertEquals(
            noOpInterceptor.javaClass.name,
            realOkHttp.interceptors.first().javaClass.name,
            "Injected interceptor should match the provided one"
        )
    }

    @Test
    fun `patchOpenAICompatibleClient falls back to httpClient field name when originalHttpClient is absent`() {
        val realOkHttp = OkHttpClient()
        val client = FakeClient_HttpClient(
            FakeClientOptions_HttpClient(FakeOkHttpWrapper(realOkHttp))
        )

        patchOpenAICompatibleClient(client, noOpInterceptor)

        assertEquals(1, realOkHttp.interceptors.size, "Interceptor should be injected via httpClient fallback path")
    }

    @Test
    fun `patchOpenAICompatibleClient is idempotent — second call does not add duplicate`() {
        val realOkHttp = OkHttpClient()
        val client = FakeClient_Original(
            FakeClientOptions_OriginalHttpClient(FakeOkHttpWrapper(realOkHttp))
        )

        patchOpenAICompatibleClient(client, noOpInterceptor)
        patchOpenAICompatibleClient(client, noOpInterceptor)

        assertEquals(1, realOkHttp.interceptors.size, "Interceptor should not be added twice")
    }

    @Test
    fun `patchOpenAICompatibleClient uses type-based fallback when okHttpClient field is renamed`() {
        val wrapperWithRenamedField = FakeOkHttpWrapperRenamedField()
        val client = FakeClient_Renamed(
            FakeClientOptions_RenamedField(wrapperWithRenamedField)
        )

        patchOpenAICompatibleClient(client, noOpInterceptor)

        assertEquals(
            1,
            wrapperWithRenamedField.renamedInternalHttpClient.interceptors.size,
            "Interceptor should be injected via type-based field lookup fallback"
        )
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // findFieldByType tests
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `findFieldByType finds OkHttpClient by type when field has expected name`() {
        val expected = OkHttpClient()
        val container = FakeOkHttpWrapper(expected)

        val found = findFieldByType(container, OkHttpClient::class.java)

        assertNotNull(found, "Should find the OkHttpClient field")
        assertEquals(expected, found, "Should return the correct OkHttpClient instance")
    }

    @Test
    fun `findFieldByType finds OkHttpClient by type when field has non-standard name`() {
        val container = FakeOkHttpWrapperRenamedField()

        val found = findFieldByType(container, OkHttpClient::class.java)

        assertNotNull(found, "Should find OkHttpClient even with a non-standard field name")
        assertEquals(container.renamedInternalHttpClient, found)
    }

    @Test
    fun `findFieldByType returns null when no field of given type exists`() {
        // A plain string holder has no OkHttpClient field
        val container = object {
            @Suppress("unused")
            val someString: String = "hello"
        }

        val found = findFieldByType(container, OkHttpClient::class.java)

        assertNull(found, "Should return null when no matching field is found")
    }

    @Test
    fun `findFieldByType searches superclass fields`() {
        open class Base {
            @Suppress("unused")
            val baseClient: OkHttpClient = OkHttpClient()
        }

        class Derived : Base()

        val instance = Derived()
        val found = findFieldByType(instance, OkHttpClient::class.java)

        assertNotNull(found, "Should find OkHttpClient declared in a superclass")
    }
}
