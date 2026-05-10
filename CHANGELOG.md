# Changelog

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
