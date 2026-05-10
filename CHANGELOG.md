# Changelog

## Unreleased

- Added OpenAI Conversations API span instrumentation: detects all 8 routes (`conversations.create`, `conversations.retrieve`, `conversations.update`, `conversations.delete`, `conversations.items.create`, `conversations.items.list`, `conversations.items.retrieve`, `conversations.items.delete`), sets `openai.api.type = "conversations"` and the correct `gen_ai.operation.name` on every span.
