# Changelog

## Unreleased

- Added Anthropic Models API tracing: `detectApiType` now returns `"models"` for `/v1/models` and `/v1/models/{id}` routes, setting `anthropic.api.type` to `"models"` and `gen_ai.operation.name` to `"models.list"` or `"models.retrieve"`. For retrieve requests the model id is extracted from the URL path into `gen_ai.request.model`. Response attributes `gen_ai.response.model`, `anthropic.model.display_name`, `anthropic.model.created_at`, and `anthropic.model.context_window` are extracted from both list (first data element) and retrieve responses.
- Added OpenAI Conversations API tracing: per-route handlers for all 8 endpoints (`conversations.create`, `conversations.retrieve`, `conversations.update`, `conversations.delete`, `conversations.items.create`, `conversations.items.list`, `conversations.items.retrieve`, `conversations.items.delete`) with span attributes `gen_ai.conversation.id`, `tracy.conversation.created_at`, `tracy.conversation.deleted`, `tracy.conversation.items.*`, `tracy.conversation.item.*`, and `tracy.request.{limit,order,after}`.
- Fixed `gen_ai.operation.name` corruption: `OpenAIApiUtils.setCommonResponseAttributes` no longer overwrites the operation name with the response `object` schema-type classifier.
- Added OTel-compliant attributes to all providers: `gen_ai.provider.name`, `server.address`, `server.port` on request spans; `http.response.status_code` (replacing legacy `http.status_code`) on response spans.
