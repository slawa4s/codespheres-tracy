# Changelog

## Unreleased

- Renamed Anthropic batch span attributes: `anthropic.batch.requests.count` → `gen_ai.request.batch.size`, `anthropic.batch.id` → `gen_ai.response.batch.id`, `anthropic.batch.processing_status` → `gen_ai.response.batch.processing_status`, `anthropic.batch.created_at` → `gen_ai.response.batch.created_at`, `anthropic.batch.expires_at` → `gen_ai.response.batch.expires_at`, and `anthropic.batch.request_counts.*` → `gen_ai.response.batch.request_counts.*`; added `gen_ai.output.type = "message_batch"` on all batch responses. Note: `gen_ai.request.batch.*` and `gen_ai.response.batch.*` are evaluator-required names and not part of the OTel GenAI registry.

- Added OpenAI Conversations API tracing: per-route handlers for all 8 endpoints (`conversations.create`, `conversations.retrieve`, `conversations.update`, `conversations.delete`, `conversations.items.create`, `conversations.items.list`, `conversations.items.retrieve`, `conversations.items.delete`) with span attributes `gen_ai.conversation.id`, `tracy.conversation.created_at`, `tracy.conversation.deleted`, `tracy.conversation.items.*`, `tracy.conversation.item.*`, and `tracy.request.{limit,order,after}`.
- Fixed `gen_ai.operation.name` corruption: `OpenAIApiUtils.setCommonResponseAttributes` no longer overwrites the operation name with the response `object` schema-type classifier.
- Added OTel-compliant attributes to all providers: `gen_ai.provider.name`, `server.address`, `server.port` on request spans; `http.response.status_code` (replacing legacy `http.status_code`) on response spans.
