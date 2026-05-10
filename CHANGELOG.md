# Changelog

## Unreleased

- Fixed `gen_ai.system` and `gen_ai.api_base` being silently dropped when `getRequestBodyAttributes` throws: both attributes are now set before the handler is called in `LLMTracingAdapter.registerRequest`.
- Added `server.address` and `server.port` span attributes (set from the outgoing request URL) to all provider spans via `LLMTracingAdapter.registerRequest`.
- Added `val port: Int` to `TracyHttpUrl` and `TracyHttpUrlImpl`, populated from the underlying HTTP URL, so callers can read the port without parsing the host string.
- Fixed `http.status_code` being unset when `getResponseBodyAttributes` throws: the attribute is now written before the handler is called in `LLMTracingAdapter.registerResponse`.
