# Changelog

## Unreleased

- Added OpenAI Conversations Items tracing: four route handlers (`ConversationItemsCreateHandler`, `ConversationItemsListHandler`, `ConversationItemRetrieveHandler`, `ConversationItemDeleteHandler`) now instrument `POST/GET/DELETE /conversations/{id}/items` endpoints with `gen_ai.operation.name` (e.g. `conversations.items.create`), `gen_ai.conversation.id` extracted from the URL path, list pagination attributes under `tracy.conversation.items.*`, single-item attributes under `tracy.conversation.item.*`, and query-parameter attributes under `tracy.request.*`.
