# Changelog

## Unreleased

- Added OpenAI Conversations API tracing: per-route handlers for all 8 endpoints (`create`, `retrieve`, `update`, `delete` conversation; `create`, `list`, `retrieve`, `delete` conversation item) with span attributes `gen_ai.conversation.id`, `tracy.conversation.created_at`, `tracy.conversation.deleted`, `tracy.conversation.items.*`, `tracy.conversation.item.*`, and `tracy.request.{limit,order,after}`.
