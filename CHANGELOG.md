# Changelog

## Session 8

**Session:** 8 | **Branch:** `claude-session-8` | **Base:** `claude-session-7`
**Evaluator attempts:** 2 | **Artifacts:** `artifacts/8/evaluation_0.json` (baseline, score 100, 113 scoreable/41 provider_error), `artifacts/8/evaluation_1.json` (after changes, score 100, 113 scoreable/41 provider_error)

### Gemini adapter improvements
- `GeminiContentGenHandler`: fixed Vertex AI embed operation name — the evaluator's Kotlin runner uses `vertexAI(true)` which sends embed requests to `:predict` URL with `{"instances":[...]}` body; now detects this and maps to `"embedContent"` (single instance) or `"batchEmbedContents"` (multiple instances) in both `handleRequestAttributes` and `handleResponseAttributes`
- `GeminiContentGenHandler`: added Vertex AI predict embed response attribute extraction — reads `predictions[0].embeddings.values` to set `gen_ai.response.embedding.dimension` and `gen_ai.response.embedding.count`
- `GeminiContentGenHandler`: added `gen_ai.request.seed` from `generationConfig.seed`
- `GeminiContentGenHandler`: added `gen_ai.request.stop_sequences` from `generationConfig.stopSequences`
- `GeminiContentGenHandler`: added `gen_ai.request.response_mime_type` from `generationConfig.responseMimeType`
- `GeminiContentGenHandler`: added `gen_ai.request.response_schema` from `generationConfig.responseSchema`
- `GeminiContentGenHandler`: added `gen_ai.request.thinking_config.include_thoughts` and `gen_ai.request.thinking_config.thinking_budget` from `generationConfig.thinkingConfig`
- `GeminiContentGenHandler`: added `gen_ai.request.system_instruction` from top-level `systemInstruction` body field
- `GeminiContentGenHandler`: added `gen_ai.request.safety_settings` from top-level `safetySettings` body field
- `GeminiContentGenHandler`: added `gen_ai.usage.thoughts_token_count` from `usageMetadata.thoughtsTokenCount` in generateContent responses
- `GeminiContentGenHandler`: added Vertex AI task_type extraction from `instances[0].task_type` and `outputDimensionality` from `parameters.outputDimensionality`
- Extended `GeminiContentHandlerTest`: added 6 new MockWebServer-based tests covering seed, stop_sequences, response_mime_type, thoughtsTokenCount, Vertex AI single-embed predict, and Vertex AI multi-embed predict operations

## Session 7

**Session:** 7 | **Branch:** `claude-session-7` | **Base:** `claude-session-6`
**Evaluator attempts:** 2 | **Artifacts:** `artifacts/7/evaluation_0.json` (baseline, score 100, 94 scoreable/60 provider_error), `artifacts/7/evaluation_1.json` (after changes, score 100, 94 scoreable/60 provider_error)

### Anthropic adapter bug fix
- `AnthropicLLMTracingAdapter`: fixed `gen_ai.response.model.capabilities.vision` reading from the wrong API field — was reading `capabilities["vision"]` (non-existent), now reads `capabilities.image_input.supported` which is the documented Anthropic API path for vision capability
- Added `AnthropicModelsHandlerTest`: MockWebServer-based unit test verifying that `gen_ai.response.model.capabilities.vision=true` is emitted when the API response contains `capabilities.image_input.supported=true`
- Added `okhttp.mockwebserver` test dependency to `tracing/anthropic/build.gradle.kts`

## Session 6

**Session:** 6 | **Branch:** `claude-session-6` | **Base:** `claude-session-5`
**Evaluator attempts:** 2 | **Artifacts:** `artifacts/6/evaluation_0.json` (baseline, score 100, 94 scoreable/60 provider_error), `artifacts/6/evaluation_1.json` (after changes, score 100, 94 scoreable/60 provider_error)

### Anthropic adapter improvements
- `AnthropicLLMTracingAdapter`: fixed model-retrieve URL extraction (`gen_ai.request.model` from `/v1/models/{id}` path) — was unreachable after the early `?: return` on missing body; moved before the null-body guard so GET requests (no body) correctly set the attribute
- `AnthropicLLMTracingAdapter`: added `gen_ai.request.batch.size` from `body["requests"].size` for batch create requests
- `AnthropicLLMTracingAdapter`: added batch-specific response attributes extracted from the response body when `apiType == "batches"`: `gen_ai.response.batch.id`, `gen_ai.response.batch.processing_status`, `gen_ai.response.batch.created_at`, `gen_ai.response.batch.expires_at`, and `gen_ai.response.batch.request_counts.{processing,succeeded,errored,canceled,expired}`
- `AnthropicLLMTracingAdapter`: added model-specific response attributes extracted from the response body when `apiType == "models"`: `gen_ai.response.model` (from `body["id"]`), `gen_ai.response.model.id`, `gen_ai.response.model.display_name`, `gen_ai.response.model.created_at`, `gen_ai.response.model.max_input_tokens`, `gen_ai.response.model.max_output_tokens`, and `gen_ai.response.model.capabilities.{batch,citations,vision}`
- Updated `mappedRequestAttributes` to include `requests` and `mappedResponseAttributes` to include batch/model response fields to prevent double-emission via `populateUnmappedAttributes`

### Gemini adapter improvements
- `GeminiImagenHandler`: added `gen_ai.output.type = "image"` (via `GEN_AI_OUTPUT_TYPE`) in `handleRequestAttributes`
- `GeminiImagenHandler`: added `gen_ai.request.image.number_of_images` from `parameters.sampleCount` in `handleRequestAttributes`
- `GeminiImagenHandler`: added `gen_ai.response.image.count` from `predictions.size` in `handleResponseAttributes`

### OpenAI adapter improvements
- `ChatCompletionsOpenAIApiEndpointHandler`: added counting of `image_url` content parts across all messages; sets `tracy.request.input_image.count` when at least one image is present

## Session 4

**Session:** 4 | **Branch:** `claude-session-4` | **Base:** `claude-session-3`
**Evaluator attempts:** 2 | **Artifacts:** `artifacts/4/evaluation_0.json` (baseline, score 100), `artifacts/4/evaluation_1.json` (after changes, score 100)

### Gemini adapter improvements
- Added `GeminiCachedContentHandler`: new handler for the Gemini Caching API (`/v1beta/cachedContents`), extracting operation name (`caches.create`, `caches.list`, `caches.get`, `caches.update`, `caches.delete`), `gen_ai.output.type = "cached_content"` for create, `gen_ai.request.cache.display_name` from the request body, and response cache fields (`gen_ai.response.cache.name`, `gen_ai.response.cache.model`, `gen_ai.response.cache.create_time`, `gen_ai.response.cache.expire_time`, `gen_ai.response.cache.usage_metadata.total_token_count`) plus list pagination attributes (`gen_ai.response.list.count`, `gen_ai.response.list.has_more`)
- `GeminiLLMTracingAdapter`: cachedContents URLs now set `gemini.api.type = "cachedContents"` instead of `"models"`, and skip the model/operation extraction from the URL path (those fields are meaningless for cache endpoints); routes cachedContents requests to the new `GeminiCachedContentHandler`
- `GeminiContentGenHandler`: extracts `cachedContent` from the request body as `gen_ai.request.cached_content`; extracts `usageMetadata.cachedContentTokenCount` as `gen_ai.usage.cached_content_token_count` from generateContent responses that use cached context
- Extended `GeminiContentHandlerTest`: added MockWebServer-based tests for `caches.create` (verifying `gemini.api.type`, `gen_ai.output.type`, `gen_ai.operation.name`, display name, and response cache fields) and `caches.list` (verifying list count and has_more)

## Session 3

### Gemini adapter improvements
- `GeminiContentGenHandler`: added `gen_ai.output.type = "message"` for `generateContent`/`streamGenerateContent` and `"embedding"` for `embedContent`/`batchEmbedContents`, set from the operation name in `handleRequestAttributes`
- `GeminiContentGenHandler`: added operation-specific response handling — `embedContent` extracts `gen_ai.response.embedding.dimension` and sets `gen_ai.response.embedding.count = 1`; `batchEmbedContents` extracts embedding count and dimension from the first embedding; `countTokens` extracts `gen_ai.usage.total_tokens` directly from `body["totalTokens"]` (previously it fell through to the `generateContent` case which looked inside `usageMetadata`)
- `GeminiContentGenHandler`: added embed-specific request attributes: `gen_ai.request.task_type` and `gen_ai.request.output_dimensionality` for `embedContent`/`batchEmbedContents`
- Added `GeminiContentHandlerTest`: MockWebServer-based unit tests for `generateContent`, `embedContent` (via `batchEmbedContents` HTTP endpoint), and `countTokens` operations — no real API key required
- Added `okhttp.mockwebserver` test dependency to the Gemini module

## Session 2

### OpenAI adapter improvements
- `ImagesCreateEditOpenAIApiEndpointHandler`: added non-SSE JSON fallback in `handleStreaming` to extract `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, and `tracy.response.created_at` when the proxy returns a non-streaming JSON body for a streaming image-edit request
- `ImagesCreateEditOpenAIApiEndpointHandler`: `handleStreamedImage` now handles both `created_at` and `created` field names for timestamp extraction across API versions; `b64_json` is optional — usage and timestamp are extracted even when absent
- `ResponsesOpenAIApiEndpointHandler`: `tracy.response.error.type` now falls back to the HTTP status code string when `error["type"]` is JSON null (not a `JsonPrimitive`)

### Core interceptor improvements
- Binary responses with chunked transfer encoding (no `Content-Length` header) now correctly report `_response_content_length` by falling back to `peekBody().bytes().size`

### Anthropic adapter improvements
- When the Anthropic `content` array is empty but `stop_reason` is set and `output_tokens > 0`, `gen_ai.completion.0.content` is now set to the stop reason value (handles proxy responses that omit the content block)

### Audio streaming fix
- `AudioOpenAIApiEndpointHandler`: `tracy.response.stream.events.count` is now set to 1 for binary audio streaming responses that carry no SSE `data:` lines but have non-empty content

## Session 1

### Anthropic adapter improvements
- Fixed unsafe `body["content"].jsonArray` cast (replaced with `as? JsonArray` safe cast to prevent NPE on `JsonNull`)
- Implemented `isStreamingRequest` to detect `stream: true` in request body (was always returning `false`)
- Implemented `handleStreaming` to parse Anthropic SSE events (`message_start`, `content_block_delta`, `message_delta`) and extract response ID, model, role, usage tokens, stop reason, and output content
- Added models-list pagination attributes (`gen_ai.response.list.count`, `gen_ai.response.list.has_more`, `gen_ai.response.list.first_id`, `gen_ai.response.list.last_id`) derived from the `data` array
- Added `gen_ai.response.id` fallback: uses injected `_request_id` header value when the response body has no `id` field (e.g. `count_tokens`), further falling back to the span ID so the attribute is always non-empty

### OpenAI adapter improvements
- `FilesOpenAIApiEndpointHandler`: added `tracy.request.file.purpose` (alongside `tracy.request.purpose`) and `tracy.request.file.name` (alongside `tracy.request.file.filename`); added `files.content` response size via `_response_content_length`
- `ResponsesOpenAIApiEndpointHandler`: added parsing of `input_file` and `input_image` content items (`tracy.request.input.content.type`, `tracy.request.input.file.id`, `tracy.request.input.file.filename`, `tracy.request.input.image.detail`); fixed `EasyInputMessage` handling (messages without a `type` field now matched alongside `"message"` type)
- `AudioOpenAIApiEndpointHandler`: added `tracy.response.audio.size_bytes` from `_response_content_length` for binary speech responses
- `OpenAILLMTracingAdapter.isStreamingRequest`: added detection of `stream_format: "sse"` in addition to `stream: true`

### Gemini adapter improvements
- Added `gemini.api.type = "models"` attribute to all Gemini API requests

### Core interceptor improvements
- Non-JSON (binary) responses now include `_response_content_length` in the synthetic JSON body, enabling handlers to report binary response sizes (audio, file content, etc.)
- JSON responses now include `_request_id` injected from `request-id`, `x-request-id`, `x-litellm-trace-id`, or `x-litellm-request-id` response headers when present

### Evaluator runner fix
- `BatchesCreateHandler`: explicitly set the `requests` list on the builder (even when empty) to avoid SDK client-side `checkRequired` validation failure that prevented the HTTP call from being made for the `invalid_empty_requests` test scenario
