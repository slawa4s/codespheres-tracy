# Changelog

All notable changes to Tracy will be documented in this file.

## Unreleased

### Session 0 — Anthropic adapter improvements

- Added SSE streaming support: `handleStreaming` now parses Anthropic Server-Sent Events (`message_start`, `message_delta`) to extract `gen_ai.response.id`, `gen_ai.response.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, and `gen_ai.response.finish_reasons` from streamed responses
- Added API-type detection: `detectApiType` derives `anthropic.api.type` from URL path segments and maps it to `gen_ai.operation.name` (`chat`, `count_tokens`, `batches`, `files`, `models`, `embeddings`)
- Added `stop_sequences` attribute: populates `gen_ai.request.stop_sequences` from request body
- Added extended thinking support: populates `gen_ai.request.thinking.type` and `gen_ai.request.thinking.budget_tokens` from request; handles `"thinking"` content blocks in responses
- Fixed tool type default: `gen_ai.tool.N.type` now defaults to `"custom"` when the API omits the field (Anthropic tools without explicit `type`)
- Added `error.type` attribute: set from response body error type field, falling back to HTTP status code string
- Fixed list pagination: derives `gen_ai.response.list.first_id` / `gen_ai.response.list.last_id` from `data` array items when the response omits top-level pagination fields (Anthropic `models/list` pattern)
- Added `count_tokens` endpoint handler: populates `gen_ai.usage.input_tokens` and attempts to read `gen_ai.response.id` from `request-id` / `x-request-id` response headers
- Fixed content-loop safety: set `gen_ai.completion.N.content` before any type cast that could skip the item; switched from `jsonObject` (throws) to safe `as? JsonObject ?: continue`
- Added response header propagation: `TracyHttpResponse` now exposes `headers: Map<String, String>` (default empty map, backward-compatible); `OpenTelemetryOkHttpInterceptor` populates this from OkHttp response headers

---

## Session 0 Summary

| Field | Value |
|---|---|
| Session ID | 0 |
| Branch | `claude-session-0` |
| Base branch | `main` |
| Evaluator attempts | 6 (`evaluation_0.json` – `evaluation_5.json`) |
| Artifacts folder | `artifacts/0/` |

**Score progression**: baseline → 95 (plateaued).

Remaining gaps confirmed external / LiteLLM-proxy limitations:
- `count_tokens` `gen_ai.response.id`: LiteLLM proxy returns no ID-bearing headers
- `tool_use_with_result` step 2 `gen_ai.completion.0.content`: model returns `content: []` (empty array), nothing to set
