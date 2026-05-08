# Changelog

## Unreleased

- Fixed `gen_ai.operation.name` being incorrectly overwritten with the raw `object` field from OpenAI response bodies (e.g. `"chat.completion"`, `"response"`) by removing that assignment from `OpenAIApiUtils.setCommonResponseAttributes()`; chat completions and the Responses API handlers now explicitly set `gen_ai.operation.name` to the OTel-specified value `"chat"`.
