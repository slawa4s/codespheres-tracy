# Changelog

## Unreleased

- Added `gen_ai.provider.name` span attribute (stable OTel GenAI registry name) to all LLM provider requests, emitting the same value as `gen_ai.system`
- Added `server.address` and `server.port` span attributes to all LLM provider requests, extracted from the request URL
- Changed HTTP status attribute key from deprecated `http.status_code` to stable `http.response.status_code`
- Extended `TracyHttpUrl` interface and `TracyHttpUrlImpl` with a `port: Int` property; updated Ktor adapter accordingly
