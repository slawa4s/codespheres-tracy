## Unreleased

- Added OpenAI Conversations API tracing support: new `CONVERSATIONS` entry in `OpenAIApiType` and `ConversationsOpenAIApiEndpointHandler` that sets `openai.api.type`, `gen_ai.conversation.id`, and `tracy.conversation.created_at` span attributes for requests to `/v1/conversations/...`
