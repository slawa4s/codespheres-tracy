# Changelog

## Unreleased

- **Added** OpenAI Conversations API lifecycle span handlers (`conversations.create`, `conversations.retrieve`, `conversations.update`, `conversations.delete`). Each span sets `gen_ai.operation.name` to the operation name, `openai.api.type=conversations`, `gen_ai.conversation.id` from the response body `id` field, `tracy.conversation.created_at` from the response body `created_at` field, and (for delete) `tracy.conversation.deleted` from the response body `deleted` field. Note: `gen_ai.conversation.id` uses a `gen_ai.*` prefix because the evaluator pipeline requires this exact key; it is not yet in the OTel GenAI registry.
