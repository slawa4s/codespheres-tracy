# Changelog

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
