# Changelog

## Unreleased

- Added OpenAI Audio Transcriptions tracing (`/v1/audio/transcriptions`): new `AUDIO` entry in `OpenAIApiType` with `AudioOpenAIApiEndpointHandler` setting `openai.api.type=audio`, `gen_ai.operation.name=audio.transcription`, `gen_ai.request.model`, `tracy.request.audio.size_bytes`, `tracy.request.audio.format`, `tracy.request.response_format`, `gen_ai.output.type`, `tracy.request.timestamp_granularities`, `tracy.response.transcription.duration_seconds`, `tracy.response.transcription.language`, and `tracy.response.transcription.words.count`.
- Added OpenAI Conversations API tracing: per-route handlers for all 8 endpoints (`conversations.create`, `conversations.retrieve`, `conversations.update`, `conversations.delete`, `conversations.items.create`, `conversations.items.list`, `conversations.items.retrieve`, `conversations.items.delete`) with span attributes `gen_ai.conversation.id`, `tracy.conversation.created_at`, `tracy.conversation.deleted`, `tracy.conversation.items.*`, `tracy.conversation.item.*`, and `tracy.request.{limit,order,after}`.
- Fixed `gen_ai.operation.name` corruption: `OpenAIApiUtils.setCommonResponseAttributes` no longer overwrites the operation name with the response `object` schema-type classifier.
- Added OTel-compliant attributes to all providers: `gen_ai.provider.name`, `server.address`, `server.port` on request spans; `http.response.status_code` (replacing legacy `http.status_code`) on response spans.
