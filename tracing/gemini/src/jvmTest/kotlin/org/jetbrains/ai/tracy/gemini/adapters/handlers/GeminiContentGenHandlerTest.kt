/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractorImpl
import org.jetbrains.ai.tracy.core.http.protocol.*
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [GeminiContentGenHandler].
 *
 * Tests every request/response attribute mapping using in-process span capture and a mock
 * [TracyHttpRequest] / [TracyHttpResponse]. No live API.
 */
class GeminiContentGenHandlerTest : BaseAITracingTest() {

    // ─── URL helpers ──────────────────────────────────────────────────────────

    private fun generateContentUrl(model: String = "gemini-2.5-flash") = TracyHttpUrlImpl(
        scheme = "https",
        host = "generativelanguage.googleapis.com",
        port = 443,
        pathSegments = listOf("v1beta", "models", "$model:generateContent"),
        url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
        parameters = emptyQueryParameters(),
    )

    private fun emptyQueryParameters() = object : TracyQueryParameters {
        override fun queryParameter(name: String): String? = null
        override fun queryParameterValues(name: String): List<String?> = emptyList()
    }

    // ─── Request / response factories ─────────────────────────────────────────

    private fun makeRequest(url: TracyHttpUrl, body: JsonObject): TracyHttpRequest =
        object : TracyHttpRequest {
            override val contentType = TracyContentType.Application.Json
            override val body = TracyHttpRequestBody.Json(body)
            override val url = url
            override val method = "POST"
        }

    private fun makeResponse(url: TracyHttpUrl, body: JsonObject): TracyHttpResponse =
        object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(body)
            override val url = url
            override val requestMethod = "POST"
            override fun isError() = false
        }

    private fun newHandler() = GeminiContentGenHandler(MediaContentExtractorImpl())

    // Tiny base64 image for inlineData test cases (single PNG pixel).
    private val PNG_PIXEL_B64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNgAAIAAAUAAen6n+gAAAAASUVORK5CYII="

    // ─── Request: smoke tests for existing coverage ───────────────────────────

    @Test
    fun `request sets gemini api type to models`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", "hi") }) } })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("models", spanData.attributes[AttributeKey.stringKey("gemini.api.type")])
    }

    @Test
    fun `request extracts canonical generationConfig fields`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            putJsonObject("generationConfig") {
                put("candidateCount", 2)
                put("maxOutputTokens", 1024)
                put("temperature", 0.7)
                put("topP", 0.95)
                put("topK", 40)
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(2L, spanData.attributes[GEN_AI_REQUEST_CHOICE_COUNT])
        assertEquals(1024L, spanData.attributes[GEN_AI_REQUEST_MAX_TOKENS])
        assertEquals(0.7, spanData.attributes[GEN_AI_REQUEST_TEMPERATURE])
        assertEquals(0.95, spanData.attributes[GEN_AI_REQUEST_TOP_P])
        assertEquals(40.0, spanData.attributes[GEN_AI_REQUEST_TOP_K])
    }

    @Test
    fun `request traces single text prompt`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", "hello world") }) }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("user", spanData.attributes[AttributeKey.stringKey("gen_ai.prompt.0.role")])
        assertEquals("hello world", spanData.attributes[AttributeKey.stringKey("gen_ai.prompt.0.content")])
    }

    @Test
    fun `request traces multi-part prompt with inlineData as JSON`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", "describe this image") })
                        add(buildJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "image/png")
                                put("data", PNG_PIXEL_B64)
                            }
                        })
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        val content = spanData.attributes[AttributeKey.stringKey("gen_ai.prompt.0.content")]
        assertNotNull(content)
        // multi-part case falls back to JSON-stringified parts array
        assertEquals(true, content.startsWith("[") && content.endsWith("]"))
        assertEquals(true, content.contains("inlineData"))
    }

    @Test
    fun `request traces tool function declarations`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            putJsonArray("tools") {
                add(buildJsonObject {
                    putJsonArray("functionDeclarations") {
                        add(buildJsonObject {
                            put("name", "get_weather")
                            put("description", "Get current weather")
                            putJsonObject("parameters") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("location") { put("type", "string") }
                                }
                            }
                        })
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("get_weather", spanData.attributes[AttributeKey.stringKey("gen_ai.tool.0.function.0.name")])
        assertEquals("Get current weather", spanData.attributes[AttributeKey.stringKey("gen_ai.tool.0.function.0.description")])
        assertEquals("object", spanData.attributes[AttributeKey.stringKey("gen_ai.tool.0.function.0.type")])
        assertNotNull(spanData.attributes[AttributeKey.stringKey("gen_ai.tool.0.function.0.parameters")])
    }

    // ─── Request: new attributes ──────────────────────────────────────────────

    @Test
    fun `request traces systemInstruction as JSON`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val sysInstr = buildJsonObject {
            putJsonArray("parts") { add(buildJsonObject { put("text", "You are a helpful assistant.") }) }
        }
        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            put("systemInstruction", sysInstr)
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(sysInstr.toString(), spanData.attributes[AttributeKey.stringKey("tracy.request.system_instruction")])
    }

    @Test
    fun `request traces toolConfig as JSON`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val toolConfig = buildJsonObject {
            putJsonObject("functionCallingConfig") {
                put("mode", "AUTO")
            }
        }
        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            put("toolConfig", toolConfig)
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(toolConfig.toString(), spanData.attributes[AttributeKey.stringKey("tracy.request.tool_config")])
    }

    @Test
    fun `request traces safetySettings as JSON`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val safety = buildJsonArray {
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HARASSMENT")
                put("threshold", "BLOCK_MEDIUM_AND_ABOVE")
            })
        }
        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            put("safetySettings", safety)
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(safety.toString(), spanData.attributes[AttributeKey.stringKey("tracy.request.safety_settings")])
    }

    @Test
    fun `request traces cachedContent string`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            put("cachedContent", "cachedContents/abc-123")
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("cachedContents/abc-123", spanData.attributes[AttributeKey.stringKey("tracy.request.cached_content")])
    }

    @Test
    fun `request traces serviceTier string`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            put("serviceTier", "PRIORITY")
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("PRIORITY", spanData.attributes[AttributeKey.stringKey("tracy.request.service_tier")])
    }

    @Test
    fun `request traces store boolean`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            put("store", true)
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(true, spanData.attributes[AttributeKey.booleanKey("tracy.request.store")])
    }

    @Test
    fun `request traces full generationConfig JSON alongside canonical fields`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val config = buildJsonObject {
            put("temperature", 0.5)
            putJsonArray("stopSequences") { add("STOP") }
            put("responseMimeType", "application/json")
        }
        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            put("generationConfig", config)
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        // canonical field still extracted
        assertEquals(0.5, spanData.attributes[GEN_AI_REQUEST_TEMPERATURE])
        // full JSON also preserved
        assertEquals(config.toString(), spanData.attributes[AttributeKey.stringKey("tracy.request.generation_config")])
    }

    @Test
    fun `request traces inlineData media content via MediaContentExtractor`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "image/png")
                                put("data", PNG_PIXEL_B64)
                            }
                        })
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        // MediaContentExtractor writes under the `custom.uploadableMediaContent.{i}.*` prefix.
        assertEquals("base64", spanData.attributes[AttributeKey.stringKey("custom.uploadableMediaContent.0.type")])
        assertEquals("input", spanData.attributes[AttributeKey.stringKey("custom.uploadableMediaContent.0.field")])
        assertEquals("image/png", spanData.attributes[AttributeKey.stringKey("custom.uploadableMediaContent.0.contentType")])
        assertEquals(PNG_PIXEL_B64, spanData.attributes[AttributeKey.stringKey("custom.uploadableMediaContent.0.data")])
    }

    // ─── Response: smoke tests for existing coverage ──────────────────────────

    @Test
    fun `response traces responseId and modelVersion`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            put("responseId", "resp-123")
            put("modelVersion", "gemini-2.5-flash-001")
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("resp-123", spanData.attributes[GEN_AI_RESPONSE_ID])
        assertEquals("gemini-2.5-flash-001", spanData.attributes[GEN_AI_RESPONSE_MODEL])
    }

    @Test
    fun `response traces single candidate role and content`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    putJsonObject("content") {
                        put("role", "model")
                        putJsonArray("parts") { add(buildJsonObject { put("text", "Hello!") }) }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("model", spanData.attributes[AttributeKey.stringKey("gen_ai.completion.0.role")])
        assertEquals("Hello!", spanData.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }

    @Test
    fun `response traces finish reason`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    putJsonObject("content") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", "stop") }) }
                    }
                    put("finishReason", "STOP")
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("STOP", spanData.attributes[AttributeKey.stringKey("gen_ai.completion.0.finish_reason")])
    }

    @Test
    fun `response traces tool calls functionCall in candidate parts`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    putJsonObject("content") {
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                putJsonObject("functionCall") {
                                    put("name", "get_weather")
                                    putJsonObject("args") { put("location", "Paris") }
                                }
                            })
                        }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("get_weather", spanData.attributes[AttributeKey.stringKey("gen_ai.completion.0.tool.0.name")])
        val args = spanData.attributes[AttributeKey.stringKey("gen_ai.completion.0.tool.0.arguments")]
        assertNotNull(args)
        assertEquals(true, args.contains("Paris"))
    }

    @Test
    fun `response traces usageMetadata input output total tokens`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonObject("usageMetadata") {
                put("promptTokenCount", 11)
                put("candidatesTokenCount", 22)
                put("totalTokenCount", 33)
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(11L, spanData.attributes[GEN_AI_USAGE_INPUT_TOKENS])
        assertEquals(22L, spanData.attributes[GEN_AI_USAGE_OUTPUT_TOKENS])
        assertEquals(33L, spanData.attributes[AttributeKey.longKey("gen_ai.usage.total_tokens")])
    }

    @Test
    fun `response traces usage promptTokensDetails and candidatesTokensDetails`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonObject("usageMetadata") {
                put("promptTokenCount", 10)
                putJsonArray("promptTokensDetails") {
                    add(buildJsonObject { put("modality", "TEXT"); put("tokenCount", 10) })
                }
                putJsonArray("candidatesTokensDetails") {
                    add(buildJsonObject { put("modality", "TEXT"); put("tokenCount", 5) })
                }
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("TEXT", spanData.attributes[AttributeKey.stringKey("gen_ai.usage.prompt_tokens_details.0.modality")])
        assertEquals(10L, spanData.attributes[AttributeKey.longKey("gen_ai.usage.prompt_tokens_details.0.token_count")])
        assertEquals("TEXT", spanData.attributes[AttributeKey.stringKey("gen_ai.usage.candidates_tokens_details.0.modality")])
        assertEquals(5L, spanData.attributes[AttributeKey.longKey("gen_ai.usage.candidates_tokens_details.0.token_count")])
    }

    // ─── Response: new attributes ─────────────────────────────────────────────

    @Test
    fun `response traces modelStatus as JSON`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val modelStatus = buildJsonObject { put("status", "AVAILABLE") }
        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            put("modelStatus", modelStatus)
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(modelStatus.toString(), spanData.attributes[AttributeKey.stringKey("tracy.response.model_status")])
    }

    @Test
    fun `response traces promptFeedback as JSON`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val feedback = buildJsonObject {
            put("blockReason", "SAFETY")
            putJsonArray("safetyRatings") {
                add(buildJsonObject {
                    put("category", "HARM_CATEGORY_DANGEROUS")
                    put("probability", "HIGH")
                })
            }
        }
        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            put("promptFeedback", feedback)
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(feedback.toString(), spanData.attributes[AttributeKey.stringKey("tracy.response.prompt_feedback")])
    }

    @Test
    fun `response traces candidate index and tokenCount`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    put("index", 0)
                    put("tokenCount", 42)
                    putJsonObject("content") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", "ok") }) }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(0L, spanData.attributes[AttributeKey.longKey("tracy.completion.0.index")])
        assertEquals(42L, spanData.attributes[AttributeKey.longKey("tracy.completion.0.token_count")])
    }

    @Test
    fun `response traces candidate finishMessage and avgLogprobs`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    put("finishMessage", "Hit max tokens")
                    put("avgLogprobs", -1.5)
                    putJsonObject("content") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", "...") }) }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("Hit max tokens", spanData.attributes[AttributeKey.stringKey("tracy.completion.0.finish_message")])
        assertEquals(-1.5, spanData.attributes[AttributeKey.doubleKey("tracy.completion.0.avg_logprobs")])
    }

    @Test
    fun `response traces candidate safetyRatings as JSON`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val ratings = buildJsonArray {
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HATE_SPEECH")
                put("probability", "NEGLIGIBLE")
            })
        }
        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    put("safetyRatings", ratings)
                    putJsonObject("content") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", "ok") }) }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(ratings.toString(), spanData.attributes[AttributeKey.stringKey("tracy.completion.0.safety_ratings")])
    }

    @Test
    fun `response traces candidate citationMetadata as JSON`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val citation = buildJsonObject {
            putJsonArray("citationSources") {
                add(buildJsonObject { put("uri", "https://example.com") })
            }
        }
        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    put("citationMetadata", citation)
                    putJsonObject("content") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", "cited") }) }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(citation.toString(), spanData.attributes[AttributeKey.stringKey("tracy.completion.0.citation_metadata")])
    }

    @Test
    fun `response traces candidate groundingMetadata and groundingAttributions as JSON`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val grounding = buildJsonObject { put("searchEntryPoint", buildJsonObject { put("renderedContent", "x") }) }
        val attributions = buildJsonArray {
            add(buildJsonObject { put("sourceId", "id-1") })
        }
        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    put("groundingMetadata", grounding)
                    put("groundingAttributions", attributions)
                    putJsonObject("content") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", "g") }) }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(grounding.toString(), spanData.attributes[AttributeKey.stringKey("tracy.completion.0.grounding_metadata")])
        assertEquals(attributions.toString(), spanData.attributes[AttributeKey.stringKey("tracy.completion.0.grounding_attributions")])
    }

    @Test
    fun `response traces candidate logprobsResult as JSON`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val logprobs = buildJsonObject {
            putJsonArray("chosenCandidates") { add(buildJsonObject { put("token", "h"); put("logProbability", -0.1) }) }
        }
        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    put("logprobsResult", logprobs)
                    putJsonObject("content") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", "h") }) }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(logprobs.toString(), spanData.attributes[AttributeKey.stringKey("tracy.completion.0.logprobs_result")])
    }

    @Test
    fun `response traces candidate urlContextMetadata as JSON`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val urlContext = buildJsonObject {
            putJsonArray("urlMetadata") { add(buildJsonObject { put("retrievedUrl", "https://example.com") }) }
        }
        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    put("urlContextMetadata", urlContext)
                    putJsonObject("content") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", "x") }) }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(urlContext.toString(), spanData.attributes[AttributeKey.stringKey("tracy.completion.0.url_context_metadata")])
    }

    @Test
    fun `response missing optional fields does not crash`() {
        val handler = newHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(generateContentUrl(), buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    putJsonObject("content") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", "min") }) }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("min", spanData.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])
        assertNull(spanData.attributes[AttributeKey.stringKey("tracy.response.prompt_feedback")])
        assertNull(spanData.attributes[AttributeKey.stringKey("tracy.completion.0.safety_ratings")])
        assertNull(spanData.attributes[AttributeKey.longKey("tracy.completion.0.token_count")])
    }
}
