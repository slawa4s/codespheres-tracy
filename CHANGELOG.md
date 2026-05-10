# Changelog

## Unreleased

- Added OpenAI Files API tracing: `FilesOpenAIApiEndpointHandler` covers `files.create` (multipart upload with `tracy.request.purpose`, `tracy.request.file.filename`, `tracy.request.file.size_bytes`, `tracy.request.expires_after.anchor/seconds`), `files.retrieve`, `files.delete` (`tracy.response.file.id`, `tracy.response.deleted`), `files.list`, and `files.content`; registered via new `FILES` entry in `OpenAIApiType`.
- Added OpenAI Conversations API tracing: per-route handlers for all 8 endpoints (`conversations.create`, `conversations.retrieve`, `conversations.update`, `conversations.delete`, `conversations.items.create`, `conversations.items.list`, `conversations.items.retrieve`, `conversations.items.delete`) with span attributes `gen_ai.conversation.id`, `tracy.conversation.created_at`, `tracy.conversation.deleted`, `tracy.conversation.items.*`, `tracy.conversation.item.*`, and `tracy.request.{limit,order,after}`.
- Fixed `gen_ai.operation.name` corruption: `OpenAIApiUtils.setCommonResponseAttributes` no longer overwrites the operation name with the response `object` schema-type classifier.
- Added OTel-compliant attributes to all providers: `gen_ai.provider.name`, `server.address`, `server.port` on request spans; `http.response.status_code` (replacing legacy `http.status_code`) on response spans.
