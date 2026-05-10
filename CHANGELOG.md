# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

- Added OpenAI Conversations API tracing: routes `POST/GET/PATCH/DELETE /v1/conversations` and `POST/GET/DELETE /v1/conversations/{id}/items[/{item_id}]` are now handled by `ConversationsOpenAIApiEndpointHandler`, setting `openai.api.type=conversations` and extracting `gen_ai.response.id`, `tracy.conversation.created_at`, and items-list pagination fields on every span.
