# Changelog

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
