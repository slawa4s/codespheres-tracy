# Changelog

## Unreleased

- Added OpenAI Conversations API tracing: per-route handlers for all 8 endpoints (`conversations.create`, `conversations.retrieve`, `conversations.update`, `conversations.delete`, `conversations.items.create`, `conversations.items.list`, `conversations.items.retrieve`, `conversations.items.delete`) with span attributes `gen_ai.conversation.id`, `tracy.conversation.created_at`, `tracy.conversation.deleted`, `tracy.conversation.items.*`, `tracy.conversation.item.*`, and `tracy.request.{limit,order,after}`.
- Fixed `gen_ai.operation.name` corruption: `OpenAIApiUtils.setCommonResponseAttributes` no longer overwrites the operation name with the response `object` schema-type classifier.
- Added OTel-compliant attributes to all providers: `gen_ai.provider.name`, `server.address`, `server.port` on request spans; `http.response.status_code` (replacing legacy `http.status_code`) on response spans.
- Added `response.done` SSE event handling in the Responses API streaming path: extracts `gen_ai.response.id`, `gen_ai.response.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `tracy.response.status`, `tracy.response.object`, `tracy.response.created_at`, and `tracy.response.completed_at` from the final streaming event.
