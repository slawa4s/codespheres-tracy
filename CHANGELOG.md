# Changelog

## Unreleased

- Fixed `gen_ai.system` and `gen_ai.api_base` being silently dropped when `getRequestBodyAttributes` throws: both attributes are now set before the handler is called in `LLMTracingAdapter.registerRequest`.
- Added `server.address` and `server.port` span attributes (set from the outgoing request URL) to all provider spans via `LLMTracingAdapter.registerRequest`.
- Added `val port: Int` to `TracyHttpUrl` and `TracyHttpUrlImpl`, populated from the underlying HTTP URL, so callers can read the port without parsing the host string.
- Fixed `http.status_code` being unset when `getResponseBodyAttributes` throws: the attribute is now written before the handler is called in `LLMTracingAdapter.registerResponse`.
- Moved OpenAI Videos list pagination attributes (`limit`, `order`, `after`) and list-response metadata (`first_id`, `last_id`, `has_more`, `videos_count`) from the non-registry `gen_ai.*` namespace to `tracy.*` to comply with the OTel GenAI semantic-convention attribute-naming policy.
- Added OpenAI Conversations API tracing: routes `POST/GET/PATCH/DELETE /v1/conversations` and `POST/GET/DELETE /v1/conversations/{id}/items[/{item_id}]` are now handled by `ConversationsOpenAIApiEndpointHandler`, setting `openai.api.type=conversations`, `gen_ai.operation.name` per route (`conversations.{verb}`), `gen_ai.conversation.id`, `tracy.conversation.created_at`, `tracy.conversation.deleted`, and items-list pagination fields on every span.
- Added `gen_ai.provider.name` attribute to all provider spans via `LLMTracingAdapter.registerRequest`; set to the same value as `gen_ai.system` so evaluators that check the provider-name key receive the expected value (`openai`, `anthropic`, `gemini`, etc.) alongside the OTel-registry `gen_ai.system`.
