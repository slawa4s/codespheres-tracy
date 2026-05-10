# Changelog

## Session 6

- **Branch**: `claude-session-6` (based on `claude-session-5`)
- **Evaluator attempts**: 1 (`artifacts/6/evaluation_0.json`)
- **Score**: 98 (unchanged; score ceiling confirmed for sixth consecutive session)

### Analysis

Ran a full baseline evaluation with 154 scenarios (113 scoreable after excluding 41 provider_error cases). The `openai/batches/list_pagination` scenario (previously PE due to Zero Data Retention policy) is now non-PE and scored 100, bringing scoreable count from 112 to 113 but leaving the overall score unchanged at 98.

The 6 remaining non-provider-error failures are all proxy/SDK limitations that Tracy cannot resolve (documented in sessions 2–5):

1. **`anthropic/batches/invalid_empty_requests`** — The Anthropic Java SDK validates client-side before any HTTP call when `requests` is empty. No OkHttp interceptor fires.

2. **`anthropic/count_tokens/basic`**, **`/with_system_prompt`**, **`/with_tools`**, **`/with_vision`** — Missing `gen_ai.response.id`. The LiteLLM proxy returns only `{"input_tokens": N}` with no `id` field and no ID response headers forwarded.

3. **`anthropic/messages/tool_use_with_result`** — Score 96/100. Missing `gen_ai.completion.0.content` (non_empty). LiteLLM returns `content: []` for the follow-up message even though `output_tokens: 2`.

No code changes to Tracy were made in this session. Score ceiling of 98 is confirmed.

---

## Session 5

- **Branch**: `claude-session-5` (based on `claude-session-4`)
- **Evaluator attempts**: 1 (`artifacts/5/evaluation_0.json`)
- **Score**: 98 (unchanged; score ceiling confirmed)

### Analysis

Ran a full baseline evaluation with 154 scenarios (112 scoreable after excluding 42 provider_error cases). Score remained at 98.

One previously-passing scenario (`openai/batches/list_pagination`, score=100) became a provider_error (score=86) due to a LiteLLM proxy change (Zero Data Retention policy enforcement). This reduces scoreable scenarios from 113 to 112, but the overall score is unaffected because PE scenarios are excluded.

The 6 remaining non-provider-error failures are all proxy/SDK limitations that Tracy cannot resolve (documented in sessions 2–4):

1. **`anthropic/batches/invalid_empty_requests`** — The Anthropic Java SDK validates client-side before any HTTP call when `requests` is empty. No OkHttp interceptor fires.

2. **`anthropic/count_tokens/basic`**, **`/with_system_prompt`**, **`/with_tools`**, **`/with_vision`** — Missing `gen_ai.response.id`. The LiteLLM proxy returns only `{"input_tokens": N}` with no `id` field and no ID response headers forwarded.

3. **`anthropic/messages/tool_use_with_result`** — Score 24/25. Missing `gen_ai.completion.0.content` (non_empty). LiteLLM returns `content: []` for the follow-up message even though `output_tokens: 2`.

No code changes to Tracy were made in this session. Score ceiling of 98 is confirmed.

---

## Session 4

- **Branch**: `claude-session-4` (based on `claude-session-3`)
- **Evaluator attempts**: 2 (`artifacts/4/evaluation_0.json`, `artifacts/4/evaluation_1.json`)
- **Score**: 98 (unchanged; score ceiling confirmed)

### Changes

#### Gemini handler improvements (`tracing/gemini`)

1. **Operation name remapping for embedding models** (`GeminiLLMTracingAdapter`): LiteLLM routes Gemini embedding models through `:predict` URLs. When the model name contains "embedding" and the operation is "predict", the adapter now remaps `gen_ai.operation.name` to "embedContent" for consistent naming.

2. **Refactored response handling** (`GeminiContentGenHandler`): Extracted separate private methods for generate-content, countTokens, and embed responses. Dispatch is now via `when` on which top-level field is present in the response body.

3. **Added missing span attributes**:
   - `gen_ai.output.type = "message"` for generateContent responses
   - `gen_ai.response.finish_reasons` list for generateContent responses
   - `gen_ai.output.type = "embedding"`, `gen_ai.response.embedding.count`, `gen_ai.response.embedding.dimension` for embed responses
   - `gen_ai.output.type = "image"`, `gen_ai.response.image.count`, `gen_ai.request.image.number_of_images` for Imagen responses (`GeminiImagenHandler`)

4. **Removed duplicate attribute setting** (`GeminiContentGenHandler`): Removed `gen_ai.request.model` and `gen_ai.operation.name` assignment from the handler since `GeminiLLMTracingAdapter` already sets them with the corrected effective operation name.

5. **MockWebServer tests** (`GeminiHandlerTest`): Added unit tests covering generateContent, countTokens, and embedContent response parsing using MockWebServer (no real API keys required). Added `okhttp-mockwebserver` dependency to the Gemini test build.

### Analysis

Baseline evaluation (113 scoreable scenarios) showed score=98. All 6 non-PE failures are the same proxy/SDK limitations documented in sessions 2–3. Gemini improvements increased partial scores in PE scenarios (e.g. embed and imagen) but those don't affect the overall score.

---

## Session 3

- **Branch**: `claude-session-3` (based on `claude-session-2`)
- **Evaluator attempts**: 1 (`artifacts/3/evaluation_0.json`)
- **Score**: 98 (unchanged from session 2)

### Analysis

Ran a full baseline evaluation with 154 scenarios (112 scoreable after excluding 42 provider_error cases). Score remained at 98. One previously-passing scenario (`openai/batches/responses_lifecycle`) became a provider_error due to a LiteLLM proxy change, but the overall score was unaffected.

The 6 remaining non-provider-error failures are all proxy/SDK limitations that Tracy cannot resolve:

1. **`anthropic/batches/invalid_empty_requests`** — Score 0/6. The Anthropic Java SDK validates client-side before any HTTP call when the batch `requests` array is empty, so no OkHttp interceptor fires and no span is emitted.

2. **`anthropic/count_tokens/basic`**, **`/with_system_prompt`**, **`/with_tools`**, **`/with_vision`** — Each missing `gen_ai.response.id` (non_empty check). The LiteLLM proxy returns only `{"input_tokens": N}` for count_tokens with no `id` field and no `request-id`/`x-request-id`/`anthropic-request-id` response headers forwarded. The real Anthropic API does return a `request-id` header, but the proxy strips it.

3. **`anthropic/messages/tool_use_with_result`** — Score 24/25. Missing `gen_ai.completion.0.content` (non_empty). LiteLLM returns `content: []` for the follow-up message even though `output_tokens: 2`, leaving Tracy no content to capture.

No code changes were made in this session. The score plateau at 98 reflects the ceiling achievable against this LiteLLM proxy configuration.

---

## Session 2

- **Branch**: `claude-session-2` (based on `claude-session-1`)
- **Evaluator attempts**: 1 (`artifacts/2/evaluation_0.json`)
- **Score**: 98 (unchanged from session 1 baseline)

### Analysis

Investigated the 6 remaining failing scenarios. All failures are caused by LiteLLM proxy behavior that Tracy cannot work around:

1. **`anthropic/batches/invalid_empty_requests`** — The Anthropic Java SDK validates locally and throws before making an HTTP call when the batch requests array is empty. No OkHttp interceptor runs, so no span is emitted. This is a runner-side issue; no Tracy code change can help.

2. **`anthropic/messages/count_tokens/basic`** and **`anthropic/messages/count_tokens/with_tools`** — The LiteLLM proxy returns `{"input_tokens": N}` with no `id` field in the body and no ID response headers (`x-request-id`, `anthropic-request-id`, etc.). Tracy already checks all plausible header names; none are forwarded. `gen_ai.response.id` cannot be set.

3. **`anthropic/messages/count_tokens/with_system_prompt`** — Same root cause as above.

4. **`anthropic/messages/tool_use_with_result`** — The follow-up message (step 2, sending tool results) causes the proxy to return `content: []` even though `output_tokens: 2`. Tracy correctly iterates the content array, but with an empty array there is nothing to emit as `gen_ai.completion.0.content`.

No code changes to Tracy were made in this session.

---

## Session 1

### OpenAI (`tracing/openai`)

- `OpenAILLMTracingAdapter`: extended to cover Audio, Batches, ChatCompletions, Conversations, Embeddings, Files, Models, Moderations, Responses, Images, and Videos endpoint handlers with full attribute extraction (operation name, model, token usage, finish reason, IDs, metadata, and endpoint-specific fields)

---

## Session 0

### Core (`tracing/core`)

- `LLMTracingAdapter`: add `gen_ai.provider.name` attribute (stable OTel semconv alias for `gen_ai.system`)
- `LLMTracingAdapter`: add `server.address` and `server.port` attributes from request URL
- `LLMTracingAdapter`: add `http.response.status_code` alongside the existing `http.status_code`
- `LLMTracingAdapter`: add `error.type` (HTTP status code string) for error responses
- `TracyHttpUrl`: add `port: Int` property to the interface and `TracyHttpUrlImpl` data class
- Build files: update JVM toolchain from 17 to 21 across all modules (`eval`, `examples`)

### Anthropic (`tracing/anthropic`)

- `AnthropicLLMTracingAdapter`: URL-based routing via `AnthropicApiType` enum to dispatch to per-endpoint handlers
- **Messages**: extract `gen_ai.operation.name = "chat"`, `anthropic.api.type = "messages"`, thinking block attributes (`gen_ai.completion.N.content`, `gen_ai.completion.N.thinking`), tool type defaulted to `"custom"` when not specified
- **Streaming**: implement `isStreamingRequest` and `handleStreaming` to parse Anthropic SSE events and set `gen_ai.response.id`, `gen_ai.response.model`, `gen_ai.response.role`, `gen_ai.output.type`, token usage, finish reasons, and completion content
- **Count tokens**: extract `gen_ai.operation.name = "count_tokens"`, `anthropic.api.type = "count_tokens"`, prompt/system/tool attributes, and `gen_ai.usage.input_tokens`
- **Batches**: extract `gen_ai.operation.name` (create/list/retrieve/cancel/delete/results), `anthropic.api.type = "batches"`, batch size, batch metadata, and request counts
- **Models**: extract `gen_ai.operation.name` (list/retrieve), `anthropic.api.type = "models"`, list pagination (`gen_ai.response.list.has_more`, `first_id`, `last_id` derived from data when not present in response), and model metadata
- **Files**: extract `gen_ai.operation.name` (create/list/retrieve/delete/content), `anthropic.api.type = "files"`, file metadata from multipart request and JSON response

### Ktor (`tracing/ktor`)

- `KtorProtocolAdapters`: pass `port` when constructing `TracyHttpUrlImpl`
