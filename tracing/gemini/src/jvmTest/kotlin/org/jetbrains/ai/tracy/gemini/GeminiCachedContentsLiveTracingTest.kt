/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini

import com.google.genai.types.CachedContent
import com.google.genai.types.Content
import com.google.genai.types.CreateCachedContentConfig
import com.google.genai.types.ListCachedContentsConfig
import com.google.genai.types.Part
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.coroutines.test.runTest
import org.jetbrains.ai.tracy.gemini.clients.instrument
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Live API tests for the Gemini cached-contents endpoints, gated on the LiteLLM proxy. Each
 * test creates its own [CachedContent] resource (via the SDK) and deletes it at the end so
 * runs don't accumulate orphaned caches.
 *
 * The Gemini Caching API requires a minimum input token count (≥1,024 for `gemini-2.5-flash`),
 * so a long context block is constructed and reused across tests.
 *
 * Coverage limitation: only **create** and **list** are exercised live. The LiteLLM
 * passthrough we use does NOT have credentials forwarded for `GenAiCacheService.{Get,
 * Delete, Update}CachedContent` and returns 401 on those RPCs. The corresponding
 * tracing logic is still covered by mock-based assertions in
 * [org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiCachedContentsHandlerTest].
 */
@EnabledIfEnvironmentVariable(
    named = "LLM_PROVIDER_URL",
    matches = "https://litellm.labs.jb.gg",
    disabledReason = "LLM_PROVIDER_URL environment variable is not https://litellm.labs.jb.gg",
)
@Tag("gemini")
class GeminiCachedContentsLiveTracingTest : BaseGeminiTracingTest() {

    private val model = "gemini-2.5-flash"

    /**
     * Builds a context block long enough to exceed the model's minimum cache token threshold.
     * `gemini-2.5-flash` requires ~1,024 input tokens. A repeated paragraph of ~5,000
     * characters (~1,250 tokens) comfortably clears it.
     */
    private val longContext: String = buildString {
        val paragraph =
            "The Apollo 11 mission was the first crewed spaceflight to land humans on the Moon. " +
                "Astronauts Neil Armstrong and Buzz Aldrin landed the Apollo Lunar Module Eagle on " +
                "July 20, 1969. Armstrong became the first person to step onto the lunar surface; " +
                "Aldrin joined him 19 minutes later. They spent about two and a quarter hours " +
                "together outside the spacecraft, collecting 21.5 kilograms of lunar material to " +
                "return to Earth. Command module pilot Michael Collins flew the Columbia in lunar " +
                "orbit while they were on the Moon's surface. "
        repeat(20) { append(paragraph) }
    }

    /**
     * Creates a cache and yields it to [block]. No explicit teardown — the LiteLLM proxy
     * returns 401 on `DeleteCachedContent` (and the SDK retries it for ~2.5 min before
     * giving up), so we rely on the cache's TTL to clean itself up instead.
     */
    private fun withCache(
        ttl: Duration = Duration.ofMinutes(2),
        displayName: String = "tracy-live-test-cache",
        block: (cache: CachedContent) -> Unit,
    ) {
        val client = createGeminiClient(timeout = Duration.ofMinutes(3)).apply { instrument(this) }
        val cache = client.caches.create(
            model,
            CreateCachedContentConfig.builder()
                .contents(Content.fromParts(Part.fromText(longContext)))
                .systemInstruction(Content.fromParts(Part.fromText("You are an expert analyzing transcripts.")))
                .displayName(displayName)
                .ttl(ttl)
                .build()
        )
        block(cache)
    }

    private fun List<SpanData>.singleWithOperation(operation: String): SpanData =
        single { it.attributes[GEN_AI_OPERATION_NAME] == operation }

    @Test
    fun `test cached content create gets traced`() = runTest(timeout = 3.minutes) {
        val client = createGeminiClient(timeout = Duration.ofMinutes(3)).apply { instrument(this) }

        client.caches.create(
            model,
            CreateCachedContentConfig.builder()
                .contents(Content.fromParts(Part.fromText(longContext)))
                .systemInstruction(Content.fromParts(Part.fromText("You are an expert analyzing transcripts.")))
                .displayName("tracy-live-test-create")
                .ttl(Duration.ofMinutes(2))
                .build()
        )

        val trace = analyzeSpans().singleWithOperation("create")

        assertEquals("cachedContents", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
        val responseId = trace.attributes[GEN_AI_RESPONSE_ID]
        assertNotNull(responseId)
        // Vertex AI returns the fully-qualified resource name (e.g.
        // "projects/{n}/locations/{loc}/cachedContents/{id}"); native Gemini returns the
        // short form ("cachedContents/{id}"). Accept either by matching the substring.
        assertTrue(
            responseId.contains("cachedContents/"),
            "gen_ai.response.id should reference cachedContents/{id}, got: $responseId",
        )
        assertNotNull(trace.attributes[GEN_AI_RESPONSE_MODEL])
        assertEquals(responseId, trace.attributes[AttributeKey.stringKey("tracy.response.cached_content.name")])
        assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.response.cached_content.create_time")])
        assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.response.cached_content.expire_time")])
    }

    @Test
    fun `test cached content list gets traced`() = runTest(timeout = 3.minutes) {
        withCache(displayName = "tracy-live-test-list") {
            val client = createGeminiClient(timeout = Duration.ofMinutes(3)).apply { instrument(this) }

            // Trigger the LIST request — the Pager fetches the first page lazily.
            val pager = client.caches.list(ListCachedContentsConfig.builder().pageSize(10).build())
            pager.iterator().hasNext() // force the first HTTP call

            val trace = analyzeSpans().singleWithOperation("list")
            assertEquals("cachedContents", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
            val count = trace.attributes[AttributeKey.longKey("gen_ai.response.list.count")]
            assertNotNull(count)
            assertTrue(count >= 1L, "Expected at least one cached content in the list response, got: $count")
            // At least the first item's name should be traced (page must have at least our cache).
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.response.list.0.name")])
        }
    }
}
