# Changelog

## Unreleased

- Added OpenAI Conversations Items span attributes: `gen_ai.operation.name` (`conversations.items.create`, `.list`, `.retrieve`, `.delete`), `openai.api.type`, `gen_ai.conversation.id`, `tracy.conversation.item.*`, `tracy.conversation.items.*` (count/first_id/last_id/has_more), `tracy.request.limit`/`order`/`after` pagination parameters, and `tracy.conversation.created_at` for delete responses.
