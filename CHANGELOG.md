# Changelog

## Unreleased

- Fixed: `OpenAIApiUtils.setCommonResponseAttributes` no longer overwrites `gen_ai.operation.name` with the response `object` field (a schema-type classifier such as `"conversation"` or `"list"`), which was corrupting operation names for Conversations and other endpoints.
