/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.http.protocol

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TracyHttpUrlTest {
    @Test
    fun `toProtocolUrl populates port from HttpUrl`() {
        val url = "https://api.openai.com:8443/v1/chat/completions".toHttpUrl()
        val protocol = url.toProtocolUrl()

        assertEquals("api.openai.com", protocol.host)
        assertEquals(8443, protocol.port)
        assertEquals("https", protocol.scheme)
    }

    @Test
    fun `toProtocolUrl uses default HTTPS port when no explicit port given`() {
        // OkHttp normalizes default ports: 443 for https, 80 for http
        val url = "https://api.openai.com/v1/chat/completions".toHttpUrl()
        val protocol = url.toProtocolUrl()

        assertEquals(443, protocol.port)
    }

    @Test
    fun `toProtocolUrl uses default HTTP port when no explicit port given`() {
        val url = "http://api.example.com/v1/chat".toHttpUrl()
        val protocol = url.toProtocolUrl()

        assertEquals(80, protocol.port)
    }

    @Test
    fun `TracyHttpUrlImpl stores port correctly`() {
        val params = object : TracyQueryParameters {
            override fun queryParameter(name: String): String? = null
            override fun queryParameterValues(name: String): List<String?> = emptyList()
        }
        val impl = TracyHttpUrlImpl(
            scheme = "https",
            host = "localhost",
            port = 1234,
            pathSegments = listOf("api", "v1"),
            parameters = params,
        )

        assertEquals(1234, impl.port)
    }

    @Test
    fun `toProtocolUrl handles non-standard port for HTTP`() {
        val url = "http://localhost:9000/api".toHttpUrl()
        val protocol = url.toProtocolUrl()

        assertEquals("localhost", protocol.host)
        assertEquals(9000, protocol.port)
    }
}
