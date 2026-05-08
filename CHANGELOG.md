# Changelog

## Unreleased

- Fixed Anthropic `batches.create` spans: `AnthropicBatchesServiceWrapper` now emits a span only for pre-HTTP client-side exceptions; successful calls and HTTP-level errors (`AnthropicServiceException`) rely solely on the OkHttp interceptor span, which carries the full set of response attributes (`http.response.status_code`, `gen_ai.response.batch.*`, etc.).
