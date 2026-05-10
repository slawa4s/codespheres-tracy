# Changelog

## Unreleased

- Added `AudioTranscriptionOpenAIApiEndpointHandler` covering `/v1/audio/transcriptions` with span attributes `gen_ai.operation.name` (`audio.transcription`), `openai.api.type` (`audio`), `gen_ai.request.model`, `gen_ai.output.type`, `tracy.request.audio.size_bytes`, `tracy.request.audio.format`, `tracy.request.response_format`, `tracy.request.timestamp_granularities`, `tracy.response.transcription.language`, `tracy.response.transcription.duration_seconds`, and `tracy.response.transcription.words.count`

- Added `gen_ai.provider.name` span attribute (stable OTel GenAI registry name) to all LLM provider requests, emitting the same value as `gen_ai.system`
- Added `server.address` and `server.port` span attributes to all LLM provider requests, extracted from the request URL
- Changed HTTP status attribute key from deprecated `http.status_code` to stable `http.response.status_code`
- Extended `TracyHttpUrl` interface and `TracyHttpUrlImpl` with a `port: Int` property; updated Ktor adapter accordingly
- Added `ConversationsOpenAIApiEndpointHandler` covering all eight OpenAI Conversations API routes (`conversations.create`, `conversations.retrieve`, `conversations.update`, `conversations.delete`, `conversations.items.create`, `conversations.items.list`, `conversations.items.retrieve`, `conversations.items.delete`) with span attributes `gen_ai.operation.name`, `openai.api.type`, `gen_ai.conversation.id`, `tracy.conversation.created_at`, `tracy.conversation.deleted`, `tracy.conversation.items.*`, `tracy.conversation.item.*`, and `tracy.request.{limit,order,after}`
- Renamed non-registry `gen_ai.*` pagination and result attributes in `ListVideosHandler` and `DeleteVideoHandler` to the `tracy.*` namespace: `gen_ai.request.after/limit/order` → `tracy.request.after/limit/order`, `gen_ai.response.first_id/last_id/has_more/videos_count` → `tracy.response.first_id/last_id/has_more/videos_count`, `gen_ai.request.video.requested_id` → `tracy.request.video.requested_id`, `gen_ai.response.deleted` → `tracy.response.deleted`
