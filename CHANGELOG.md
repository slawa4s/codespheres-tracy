# Changelog

## [Unreleased] – Session 0

### Added

- `TracyHttpUrl.port: Int` property added to `TracyHttpUrl` interface and `TracyHttpUrlImpl`; `toProtocolUrl()` now extracts port from OkHttp and Ktor URLs.
- `LLMTracingAdapter.registerRequest()` now sets `gen_ai.provider.name`, `server.address`, and `server.port` cross-cutting attributes on every span.
- `LLMTracingAdapter.registerResponse()` now sets `http.response.status_code` (was `http.status_code`) and `error.type` on every span.
- `AnthropicLLMTracingAdapter`: URL-based routing for `anthropic.api.type` and `gen_ai.operation.name` across all Anthropic API endpoints (messages, count_tokens, batches, files, models).
- `AnthropicLLMTracingAdapter`: `gen_ai.request.stop_sequences` from request body.
- `AnthropicLLMTracingAdapter`: Extended thinking request attributes (`gen_ai.request.thinking.type`, `gen_ai.request.thinking.budget_tokens`).
- `AnthropicLLMTracingAdapter`: Extended thinking response content blocks (`gen_ai.completion.N.thinking`, `gen_ai.completion.N.type: "thinking"`).
- `AnthropicLLMTracingAdapter`: Tool type defaults to `"custom"` when Anthropic API omits the field.
- `AnthropicLLMTracingAdapter`: Streaming support via `isStreamingRequest()` and `handleStreaming()` — parses SSE `message_start` and `message_delta` events to populate `gen_ai.response.id`, `gen_ai.output.type`, `gen_ai.response.role`, `gen_ai.response.model`, token counts, and finish reasons.
- `AnthropicLLMTracingAdapter`: Batch API response attributes (`gen_ai.response.batch.id`, `gen_ai.response.batch.processing_status`, `gen_ai.response.batch.created_at`, `gen_ai.response.batch.expires_at`, `gen_ai.request.batch.size`, `gen_ai.response.batch.request_counts.*`).
- `AnthropicLLMTracingAdapter`: Files API response attributes (`gen_ai.response.file.id`, `.filename`, `.mime_type`, `.size_bytes`, `.downloadable`, `.created_at`; `gen_ai.request.file.*` from multipart form data).
- `AnthropicLLMTracingAdapter`: Models API response attributes — list pagination (`gen_ai.response.list.count`, `.has_more`, `.first_id`, `.last_id` synthesized from data items when absent) and single-model attributes (`gen_ai.response.model.*`, `gen_ai.response.model.capabilities.*`).
- `AnthropicLLMTracingAdapter`: count_tokens response parsing (`gen_ai.usage.input_tokens` from top-level field).
- `AnthropicLLMTracingAdapter`: Models retrieve — `gen_ai.request.model` extracted from URL path.

### Fixed

- Java toolchain changed from 17 to 21 across all `tracing/*` and `plugin/*` Gradle modules (container only has JDK 21).
- `http.status_code` attribute renamed to `http.response.status_code` to match OTel semconv.

### Evaluation Results

| Attempt | Score | Notes |
|---------|-------|-------|
| 0 (baseline) | 34 | Before any changes |
| 1 | 94 | After cross-cutting + Anthropic routing fixes |
| 2 | 95 | After list pagination synthesis for models/batches/files |
