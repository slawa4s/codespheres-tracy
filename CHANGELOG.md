# Changelog

## Unreleased

### Changed

- **`LLMTracingAdapter`**: Added `server.address`, `server.port`, and `gen_ai.provider.name` span
  attributes to every request span. Changed HTTP status attribute from `http.status_code` to
  `http.response.status_code` (OTel HTTP semconv alignment). Added `error.type` alongside
  `gen_ai.error.type` in error responses.

### Added

- **`AnthropicLLMTracingAdapter`**: Complete rewrite with per-endpoint routing. The adapter now
  detects the Anthropic API endpoint from the request URL and sets `anthropic.api.type` (messages,
  count_tokens, batches, files, models) and a unique `gen_ai.operation.name` for each route
  (chat, count_tokens, batches.create, batches.list, batches.retrieve, batches.cancel,
  batches.delete, batches.results, files.create, files.list, files.retrieve, files.delete,
  files.content, models.list, models.retrieve).

- **Messages endpoint**: Captures temperature, model, max_tokens, metadata, service_tier, system
  prompts, top_k, top_p, stop_sequences, extended thinking config, message history, and tool
  definitions (`gen_ai.tool.N.*`). Tool `type` defaults to `"custom"` when omitted from the
  Anthropic JSON payload (per OTel GenAI spec). Response captures id, type, role, model, content
  blocks (text, thinking, tool_use), finish reasons, and token usage including cache tokens.
  Streaming SSE parsing captures the same attributes from the event stream.

- **Count tokens endpoint**: Captures model, system, messages, tools on the request side;
  `gen_ai.usage.input_tokens` on the response side.

- **Batches endpoint**: Captures batch size on request. Response captures list pagination
  (`gen_ai.response.list.*`) or single-batch attributes (`gen_ai.response.batch.*`) including
  processing_status and per-state request_counts.

- **Files endpoint**: Captures file size, filename, and MIME type on multipart upload requests.
  Response captures list pagination or single-file attributes (`gen_ai.response.file.*`).

- **Models endpoint**: Captures model ID from URL path on retrieve requests. Response captures
  list pagination or single-model attributes (`gen_ai.response.model.*`) including capabilities
  (batch, citations, vision).

- **`AnthropicAdapterAttributesTest`**: New MockWebServer-based test class covering all new
  attributes — `anthropic.api.type`, `gen_ai.operation.name`, `server.address`, `server.port`,
  `http.response.status_code`, `gen_ai.tool.0.type = "custom"`, error attributes, count tokens
  response, batch create response, and models list response.

- **`eval/build.gradle.kts`**: Updated JVM toolchain from 17 → 21 (aarch64 compatibility).
