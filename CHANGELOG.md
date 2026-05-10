# Changelog

## Unreleased

- Fixed baseline span attributes (`gen_ai.provider.name`, `server.address`, `server.port`, `gen_ai.api_base`, `gen_ai.system`) now always written even when `getRequestBodyAttributes()` throws, by moving them before that call in `LLMTracingAdapter.registerRequest()`.
- Fixed error spans: `error.type` (OTel registry attribute) is now emitted alongside `gen_ai.error.type` in `getResponseErrorBodyAttributes()`.

- Added OpenAI Conversations API tracing: per-route handlers for all 8 endpoints (`conversations.create`, `conversations.retrieve`, `conversations.update`, `conversations.delete`, `conversations.items.create`, `conversations.items.list`, `conversations.items.retrieve`, `conversations.items.delete`) with span attributes `gen_ai.conversation.id`, `tracy.conversation.created_at`, `tracy.conversation.deleted`, `tracy.conversation.items.*`, `tracy.conversation.item.*`, and `tracy.request.{limit,order,after}`.
- Fixed `gen_ai.operation.name` corruption: `OpenAIApiUtils.setCommonResponseAttributes` no longer overwrites the operation name with the response `object` schema-type classifier.
- Added OTel-compliant attributes to all providers: `gen_ai.provider.name`, `server.address`, `server.port` on request spans; `http.response.status_code` (replacing legacy `http.status_code`) on response spans.
