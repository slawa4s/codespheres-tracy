# Changelog

## Unreleased

- Added OpenAI Conversations API span instrumentation: `CreateConversationHandler`, `RetrieveConversationHandler`, `UpdateConversationHandler`, and `DeleteConversationHandler` now set `gen_ai.operation.name` (`conversations.{verb}`), `gen_ai.conversation.id` (OTel GenAI registered attribute), `tracy.conversation.created_at`, and `tracy.conversation.deleted` (delete only) for all conversation lifecycle operations.
