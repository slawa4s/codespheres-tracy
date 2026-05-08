# Changelog

All notable changes to Tracy will be documented in this file.

## Unreleased

### Added

- **OpenAI Audio API handler** (`AudioOpenAIApiEndpointHandler`): spans for `audio.transcription`, `audio.translation`, and `audio.speech` operations with request metadata (model, response_format, language, temperature, audio size/format, voice, speed) and response fields (duration, language, word count for verbose transcriptions, audio size for speech).
- **OpenAI Embeddings API handler** (`EmbeddingsOpenAIApiEndpointHandler`): spans for `embeddings` operations with encoding format, dimensions, embedding count, and input token usage.
- **OpenAI Files API handler** (`FilesOpenAIApiEndpointHandler`): spans for `files.create`, `files.retrieve`, `files.list`, `files.delete`, and `files.content` operations with file metadata (id, filename, purpose, size, status).
- **OpenAI Batches API handler** (`BatchesOpenAIApiEndpointHandler`): spans for `batches.create`, `batches.retrieve`, `batches.list`, and `batches.cancel` operations with batch metadata and request count stats.
- **OpenAI Conversations API handler** (`ConversationsOpenAIApiEndpointHandler`): spans for conversation CRUD and conversation items sub-resource operations.
- **OpenAI Models API handler** (`ModelsOpenAIApiEndpointHandler`): spans for `models.list`, `models.retrieve`, and `models.delete` operations.
- **OpenAI Moderations API handler** (`ModerationsOpenAIApiEndpointHandler`): spans for `moderations` operations with input type detection and flagged category results.
- **Gemini Cached Contents handler** (`GeminiCachedContentsHandler`): spans for `/v1beta/cachedContents` CRUD operations.
- `contentLength` property on `TracyHttpResponse` interface (default `null`), exposed from OkHttp `Content-Length` header for binary responses.
- `port` property on `TracyHttpUrl` interface for server port tracking.
- `gen_ai.provider.name`, `server.address`, `server.port` attributes emitted on every span via `LLMTracingAdapter`.
- `error.type` attribute set to HTTP status code string on error responses.

### Fixed

- `http.response.status_code` attribute name corrected (was `http.status_code`).
- OpenAI Responses API: `object`, `store`, `truncation`, `parallel_tool_calls` fields now auto-flow as `tracy.response.*` / `tracy.request.*` instead of being blocked by `mappedAttributes`.
- OpenAI Responses API: nested `reasoning` and `text` format attributes now extracted correctly.
- Gemini adapter: `gen_ai.output.type` set based on operation (`message`, `embedding`, `image`).
- JVM toolchain updated from 17 to 21 in compiler plugin modules (fixes build on aarch64).
