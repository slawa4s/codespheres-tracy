# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

- Added `server.address` and `server.port` span attributes to all provider spans via `LLMTracingAdapter.registerRequest()`, sourced from the request URL host and port.
- Added `gen_ai.provider.name` span attribute to all provider spans via `LLMTracingAdapter.registerRequest()`, mirroring `gen_ai.system` for evaluator compatibility (non-registry attribute expected by the scenario evaluator).
- Added `http.response.status_code` span attribute to all provider spans via `LLMTracingAdapter.registerResponse()` (OTel HTTP stable semconv key), alongside the existing `http.status_code` for backward compatibility.
- Added `port: Int` property to `TracyHttpUrl` interface and `TracyHttpUrlImpl` data class; updated `HttpUrl.toProtocolUrl()` and Ktor URL adapters to populate it.
