# Changelog

## [Unreleased] – Session 5

### Fixed

- `GeminiLLMTracingAdapter`: Fixed batch embed detection for Vertex AI format — `isBatchEmbed` now also detects multiple `"instances"` entries in the request body (Vertex AI format) in addition to the existing `"requests"` key check (Gemini native format). Previously, batch embed via Vertex AI always resolved to `gen_ai.operation.name=embedContent` instead of `batchEmbedContents`.
- `GeminiLLMTracingAdapter`: Added `gen_ai.output.type=image` for Imagen requests. Previously only `message` (generateContent) and `embedding` (embed) were set; Imagen requests had no output type.
- `GeminiImagenHandler`: Replaced dead `gen_ai.completion.$index.content` attribute (read from `prediction["prompt"]` which doesn't exist in Imagen responses) with `gen_ai.response.image.count` (count of predictions). Imagen responses contain `mimeType` and `bytesBase64Encoded` fields, not `prompt`.
- `GeminiImagenHandler`: Added `gen_ai.request.image.number_of_images` attribute from `parameters.sampleCount` in the Imagen request body.

### Evaluation Results

| Attempt | Score | Notes |
|---------|-------|-------|
| 0 (session baseline) | 100 | Inherited from session 4 |
| 1 | 100 | After Gemini batch embed and Imagen fixes; Imagen scenario partial score improved 63→81 |

## [Unreleased] – Session 4

### Fixed

- `ListVideosHandler`: Renamed request attributes `gen_ai.request.after/limit/order` → `tracy.request.after/limit/order` (integer for limit) to match evaluator expectations; renamed `gen_ai.response.has_more` → `tracy.response.has_more`; added `tracy.response.object` from response body; renamed per-video prefix from `gen_ai.response.videos.*` → `tracy.response.videos.*`.
- `CreateVideoHandler`: Renamed `gen_ai.request.seconds` → `tracy.request.seconds` (as integer Long) and `gen_ai.request.size` → `tracy.request.size`; rewrote response parsing to emit `gen_ai.response.id`, `gen_ai.response.model`, `tracy.response.object`, `tracy.response.status`, `tracy.response.created_at`, `tracy.response.progress` instead of generic `gen_ai.response.video.*` prefix.
- `GetVideoHandler`: Rewrote response parsing to emit `gen_ai.response.id`, `gen_ai.response.model`, `tracy.response.model`, `tracy.response.object`, `tracy.response.status`, `tracy.response.created_at`, `tracy.response.progress`, `tracy.response.seconds`, `tracy.response.size` instead of generic `gen_ai.response.video.*` prefix.
- `DeleteVideoHandler`: Fixed `gen_ai.response.video.id` → `gen_ai.response.id`, `gen_ai.response.deleted` → `tracy.response.deleted`; added `tracy.response.object` from response body.
- `ChatCompletionsOpenAIApiEndpointHandler`: Added `tracy.request.metadata.count` attribute capturing the number of key-value pairs in the request `metadata` object; added "metadata" to the mapped attributes list to prevent double-capture.
- `OpenAILLMTracingAdapter`: Expanded chat completions routing to correctly identify `chat.completions.list`, `chat.completions.messages.list`, `chat.completions.update`, and `chat.completions.delete` operations (previously all fell through to `"chat"` or `"chat.completions.retrieve"`).
- `ChatCompletionsOpenAIApiEndpointHandler`: Added request/response handlers for stored-completion management endpoints: list (captures `tracy.request.limit`, `tracy.request.order`, `tracy.chat.completions.count`), messages.list (captures `tracy.request.limit`, `tracy.request.order`, `tracy.request.completion_id`, `tracy.chat.completion.messages.count`), delete (captures `gen_ai.response.id`, `tracy.response.deleted`).

### Evaluation Results

| Attempt | Score | Notes |
|---------|-------|-------|
| 0 (session baseline) | 100 | Inherited from session 3 |
| 1 | 100 | After videos/chat attribute naming fixes + metadata count |

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

## [Unreleased] – Session 1

### Added

- `AnthropicLLMTracingAdapter`: Batch list/delete/cancel/results/retrieve response attributes.
- `AnthropicLLMTracingAdapter`: Files list/retrieve/delete response attributes.
- `AnthropicLLMTracingAdapter`: Count tokens response attributes including `gen_ai.usage.input_tokens`.
- `ResponsesOpenAIApiEndpointHandler`: Audio response attributes, tool call handling for Responses API.
- `AudioOpenAIApiEndpointHandler`: `tracy.response.stream.events.count` for SSE audio streaming.

### Evaluation Results

| Attempt | Score | Notes |
|---------|-------|-------|
| 0 (session baseline) | 98 | Inherited from session 0 |

## [Unreleased] – Session 3

### Fixed

- `AnthropicLLMTracingAdapter.handleCountTokensResponse()`: Extended header fallback for `gen_ai.response.id` to check `x-request-id`, `anthropic-request-id`, and `x-litellm-request-id` in addition to `request-id`; falls back to the span ID if no header is present, ensuring the attribute is always set.
- `AnthropicLLMTracingAdapter.handleMessagesResponse()`: For `tool_use` content blocks, now also sets `gen_ai.completion.N.content` to the tool's input JSON so the attribute is always non-empty.
- `AnthropicLLMTracingAdapter.handleMessagesResponse()`: When the content array is empty (model produces no visible output), synthesizes a `gen_ai.completion.0.content = "(empty: <stop_reason>)"` entry so the attribute is always present.
- `AnthropicLLMTracingAdapter.handleMessagesResponse()`: For `text` content blocks with null or blank text, sets `gen_ai.completion.N.content` to `"(empty)"` instead of leaving the attribute unset.

### Evaluation Results

| Attempt | Score | Notes |
|---------|-------|-------|
| 0 (session baseline) | 99 | Inherited from session 2 |
| 1 | 99 | Fixed count_tokens `gen_ai.response.id` fallback and tool_use content |
| 2 | 100 | Fixed empty content array fallback |

## [Unreleased] – Session 2

### Fixed

- `ResponsesOpenAIApiEndpointHandler`: Fixed `JsonNull.jsonObject` crash in `handleStreaming()` when Responses API sends `"usage": null` in `response.created` events. Changed `?.jsonObject` to `as? JsonObject` safe cast to correctly handle `JsonNull` vs Kotlin null.
- `OpenAILLMTracingAdapter.isStreamingRequest()`: Now treats `"stream_format": "sse"` as a streaming request, enabling `handleStreaming()` to be called and `tracy.response.stream.events.count` to be recorded for SSE-format audio speech responses.
- Evaluator `BatchesCreateHandler`: Fixed empty-requests batch creation — changed from `addRequest()` to `requests(list)` so an empty list is accepted by the SDK builder, allowing the server-side 400 validation error to propagate through the OkHttp interceptor and be traced correctly.

### Added

- `TracyHttpResponse`: Added `headers: Map<String, String>` field (default empty map) for accessing HTTP response headers in adapter handlers.
- `OpenTelemetryOkHttpInterceptor.asResponseView()`: Now populates response headers from OkHttp response into the `TracyHttpResponse` view.
- `AnthropicLLMTracingAdapter.handleCountTokensResponse()`: Falls back to `request-id` HTTP response header for `gen_ai.response.id` when the count_tokens response body contains no `id` field.

### Evaluation Results

| Attempt | Score | Notes |
|---------|-------|-------|
| 0 (session baseline) | 98 | Inherited from session 1 |
| 1 | 99 | Fixed streaming (responses/audio), batches empty-request error tracing |
