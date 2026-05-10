# Changelog

## Unreleased

- Added `server.address` and `server.port` span attributes to all LLM provider spans, populated from the request URL host and port via the new `port: Int` field on `TracyHttpUrl` / `TracyHttpUrlImpl`.
- Added `gen_ai.provider.name` span attribute alongside `gen_ai.system` in `LLMTracingAdapter.registerRequest`; both carry the same provider system string for evaluator compatibility.
- Changed HTTP status attribute key from legacy `http.status_code` to `http.response.status_code` (OTel HTTP semantic conventions ≥ 1.23) in `LLMTracingAdapter.registerResponse`.
