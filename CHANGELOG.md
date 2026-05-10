# Changelog

## Unreleased

- Added OpenAI Batches API tracing: `batches.create`, `batches.retrieve`, `batches.cancel`, and `batches.list` routes with span attributes `openai.api.type`, `gen_ai.operation.name`, `tracy.request.batch.*` (endpoint, completion_window, input_file.id, output_expires_after.anchor/seconds), `tracy.request.metadata.keys`, and `tracy.batch.*` (id, status, created_at, request_counts.total/completed/failed).
- Added OpenAI Conversations API tracing: per-route handlers for all 8 endpoints (`conversations.create`, `conversations.retrieve`, `conversations.update`, `conversations.delete`, `conversations.items.create`, `conversations.items.list`, `conversations.items.retrieve`, `conversations.items.delete`) with span attributes `gen_ai.conversation.id`, `tracy.conversation.created_at`, `tracy.conversation.deleted`, `tracy.conversation.items.*`, `tracy.conversation.item.*`, and `tracy.request.{limit,order,after}`.
- Fixed `gen_ai.operation.name` corruption: `OpenAIApiUtils.setCommonResponseAttributes` no longer overwrites the operation name with the response `object` schema-type classifier.
- Added OTel-compliant attributes to all providers: `gen_ai.provider.name`, `server.address`, `server.port` on request spans; `http.response.status_code` (replacing legacy `http.status_code`) on response spans.
