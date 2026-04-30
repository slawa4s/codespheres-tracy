/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.models.messages.batches.BatchCreateParams
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Tag("anthropic")
class AnthropicBatchTracingTest : BaseAnthropicTracingTest() {

    /**
     * Verifies that Tracy captures a span for a batch create call even when the request contains
     * invalid data that will be rejected server-side.
     *
     * Key requirements tested:
     * 1. [instrument] is called **before** any batch operations are invoked.
     *    [org.jetbrains.ai.tracy.core.patchOpenAICompatibleClient] patches the OkHttp client held
     *    in `clientOptions`, so calling `instrument()` after the batch call would mean Tracy's
     *    interceptor is not yet in place when the HTTP request is made and no span would be created.
     * 2. The requests list contains **at least one entry** so that the HTTP call is actually made.
     *    An empty requests array may be validated client-side by the Anthropic SDK before any HTTP
     *    call is issued, meaning Tracy's interceptor would never fire and no span would be captured.
     *    Using an entry with a structurally invalid model name instead ensures the call reaches the
     *    network and Tracy captures the resulting server-side 400 error.
     */
    @Test
    fun `test batch create with invalid request entry is traced`() = runTest {
        // instrument() MUST be called before using the client for batch operations so that
        // Tracy's OkHttp interceptor is wired into the shared HTTP client before any call is made.
        val client = createAnthropicClient().apply { instrument(this) }

        // Use at least one structurally-invalid request entry rather than an empty list.
        // An empty `requests` array would be validated client-side (no HTTP call → no span).
        // A request with a non-existent model passes client-side validation, reaches the network,
        // and the server returns a 400 error that Tracy can capture.
        val params = BatchCreateParams.builder()
            .addRequest(
                BatchCreateParams.Request.builder()
                    .customId("test-req-1")
                    .params(
                        BatchCreateParams.Request.Params.builder()
                            .model("[non-existent model!]")
                            .addUserMessage("Say hi!")
                            .maxTokens(1)
                            .build()
                    )
                    .build()
            )
            .build()

        try {
            client.messages().batches().create(params)
        } catch (_: Exception) {
            // suppress: a server-side error is expected for the non-existent model
        }

        val traces = analyzeSpans()
        val trace = traces.firstOrNull()
        assertNotNull(trace, "Expected a span to be created for the batch create call")

        assertEquals(
            "batches.create",
            trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")],
        )
        assertEquals(StatusCode.ERROR, trace.status.statusCode)
    }
}
