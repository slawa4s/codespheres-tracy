# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

- Added OpenAI Conversations API routing: registered `CONVERSATIONS` in `OpenAIApiType` and introduced `ConversationsOpenAIApiEndpointHandler` with per-operation sub-route detection covering conversation and item lifecycle operations (`create`, `retrieve`, `update`, `delete`, `create_item`, `list_items`, `retrieve_item`, `delete_item`). Span attributes recorded include `openai.conversation.id`, `openai.conversation.item.id`, `openai.conversation.item.type`, `openai.conversation.item.role`, and pagination fields.
