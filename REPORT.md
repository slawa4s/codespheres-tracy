
# Summary


| Provider | Endpoint Class | Endpoint Route                                     | New | Implemented by                                                                                        | Note                                                                                   |
|----------|----------------|----------------------------------------------------|-----|-------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| OpenAI   | audio          | `/audio/transcriptions`                            | ✅   | `org.jetbrains.ai.tracy.openai.adapters.handlers.audio.AudioOpenAIApiEndpointHandler`                 |                                                                                        |
|          |                | `/audio/translations`                              |     |                                                                                                       |                                                                                        |
|          |                | `/audio/speech`                                    |     |                                                                                                       |                                                                                        |
|          |                | `/audio/voices`                                    |     |                                                                                                       | Not generated because openai-java:4.5.0 doesn't expose the route                       |
|          | batches        | `/batches`                                         | ✅   | `org.jetbrains.ai.tracy.openai.adapters.handlers.batches.BatchesOpenAIApiEndpointHandler`             | Same path used for `POST` (create) and `GET` (list)                                    |
|          |                | `/batches/{batch_id}`                              |     |                                                                                                       | `GET` (retrieve)                                                                       |
|          |                | `/batches/{batch_id}/cancel`                       |     |                                                                                                       | `POST` (cancel)                                                                        |
|          | conversations  | `/conversations`                                   | ✅   | `org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.ConversationsOpenAIApiEndpointHandler` | `POST` (create)                                                                        |
|          |                | `/conversations/{conversation_id}`                 |     |                                                                                                       | `GET` (retrieve), `DELETE`. `POST` (update) is **not** dispatched by the handler — gap |
|          |                | `/conversations/{conversation_id}/items`           |     |                                                                                                       | Bonus: handler also covers `POST` (items.create) and `GET` (items.list)                |
|          |                | `/conversations/{conversation_id}/items/{item_id}` |     |                                                                                                       | Bonus: handler also covers `GET` (items.retrieve) and `DELETE` (items.delete)          |
|          | files          | `/files`                                           | ✅   | `org.jetbrains.ai.tracy.openai.adapters.handlers.files.FilesOpenAIApiEndpointHandler`                 | `GET` (list), `POST` (create, multipart)                                               |
|          |                | `/files/{file_id}`                                 |     |                                                                                                       | `GET` (retrieve), `DELETE`                                                             |
|          |                | `/files/{file_id}/content`                         |     |                                                                                                       | `GET` (content) — binary response                                                      |
|          | models         | `/models`                                          | ✅   | `org.jetbrains.ai.tracy.openai.adapters.handlers.models.ModelsOpenAIApiEndpointHandler`               | `GET` (list)                                                                           |
|          |                | `/models/{model}`                                  |     |                                                                                                       | `GET` (retrieve). `DELETE` is **not** dispatched by the handler — gap                  |
|          | moderations    | `/moderations`                                     | ✅   | `org.jetbrains.ai.tracy.openai.adapters.handlers.moderations.ModerationsOpenAIApiEndpointHandler`     | `POST` (create)                                                                        |
| Anthropic | count_tokens  | `/v1/messages/count_tokens`                        | ✅   | `org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicCountTokensHandler`                      | `POST`. `gen_ai.response.id` is taken from the `x-request-id` header (no body `id`)    |
|           | batches        | `/v1/messages/batches`                             | ✅   | `org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicListEndpointHandler`                     | `POST` (create), `GET` (list). **One handler dispatches across batches/models/files — see §9 design note** |
|           |                | `/v1/messages/batches/{message_batch_id}`          |     |                                                                                                       | `GET` (retrieve), `DELETE`                                                              |
|           |                | `/v1/messages/batches/{message_batch_id}/cancel`   |     |                                                                                                       | `POST` (cancel)                                                                         |
|           |                | `/v1/messages/batches/{message_batch_id}/results`  |     |                                                                                                       | `GET` (results, JSONL). Operation name **collides** with `batches.retrieve` — gap       |
|           | models         | `/v1/models`                                       | ✅   | (same `AnthropicListEndpointHandler`)                                                                 | `GET` (list)                                                                            |
|           |                | `/v1/models/{model_id}`                            |     |                                                                                                       | `GET` (retrieve). **Bug:** handler reads `capabilities.vision` (not a documented key)   |
|           | files          | `/v1/files`                                        | ✅   | (same `AnthropicListEndpointHandler`)                                                                 | `POST` (upload, multipart), `GET` (list)                                                |
|           |                | `/v1/files/{file_id}`                              |     |                                                                                                       | `GET` (retrieve_metadata), `DELETE`. **Bug:** dead-code branch checking `body.deleted`  |
|           |                | `/v1/files/{file_id}/content`                      |     |                                                                                                       | `GET` (content, binary). Operation name **collides** with `files.retrieve` — gap        |
|           | messages       | `/v1/messages`                                     | ✅   | `org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicMessagesHandler`                         | `POST` (create) + SSE streaming. **Refactor**: previously inlined in `AnthropicLLMTracingAdapter`; now in its own handler. Hand-written, not breeder-generated |


# Attribute Mapping


## OpenAI

### 1. `AudioOpenAIApiEndpointHandler`

Info:
1. Request type: form-data
1. Response type: JSON
1. Covers endpoints:
   1. `/audio/transcriptions`
   2. `/audio/translations`
   3. `/audio/speech`
4. **Attributes coverage: 4/20 = 20%**

| Original attribute         | Source                                                  | Mapped to attribute(s)                                               | Specification type | Note                                              |
|----------------------------|---------------------------------------------------------|----------------------------------------------------------------------|--------------------|---------------------------------------------------|
| `model`                    | req                                                     | `gen_ai.request.model`                                               | GenAI              |                                                   |
| `file`                     | req                                                     | `gen_ai.request.audio.size_bytes`,</br>`gen_ai.request.audio.format` | Custom             | Attempted GenAI, undefined by spec                |
| `chunking_strategy`        | req                                                     | ❌                                                                    |                    |                                                   |
| `include`                  | req                                                     | ❌                                                                    |                    |                                                   |
| `known_speaker_names`      | req                                                     | ❌                                                                    |                    |                                                   |
| `known_speaker_references` | req                                                     | ❌                                                                    |                    |                                                   |
| `language`                 | req                                                     | ❌                                                                    |                    |                                                   |
| `prompt`                   | req                                                     | ❌                                                                    |                    |                                                   |
| `response_format`          | req                                                     | `gen_ai.request.response_format`                                     | Custom             | Attempted GenAI, `gen_ai.output.type` recommended |
| `stream`                   | req                                                     | ❌                                                                    |                    |                                                   |
| `temperature`              | req                                                     | ❌                                                                    |                    |                                                   |
| `timestamp_granularities`  | req                                                     | ❌                                                                    |                    |                                                   |
| `text`                     | resp:all                                                | `gen_ai.response.text`                                               | Custom             | Attempted GenAI, undefined by spec                |
| `logprobs`                 | resp:`Transcription`                                    | ❌                                                                    |                    |                                                   |
| `usage`                    | resp:all                                                | ❌                                                                    |                    |                                                   |
| `duration`                 | resp:`TranscriptionDiarized`</br>`TranscriptionVerbose` | ❌                                                                    |                    |                                                   |
| `segments`                 | resp:`TranscriptionDiarized`</br>`TranscriptionVerbose` | ❌                                                                    |                    |                                                   |
| `task`                     | resp:`TranscriptionDiarized`                            | ❌                                                                    |                    |                                                   |
| `language`                 | resp:`TranscriptionVerbose`                             | ❌                                                                    |                    |                                                   |
| `words`                    | resp:`TranscriptionVerbose`                             | ❌                                                                    |                    |                                                   |

### 2. `BatchesOpenAIApiEndpointHandler`

Info:
1. Request type: JSON (`POST /batches`); none for `GET`/`POST cancel` (only path/query params)
1. Response type: JSON (`Batch` object — same schema across `create`, `retrieve`, `cancel`; nested under `data` for `list`)
1. Covers endpoints:
   1. `POST /batches`
   2. `GET /batches/{batch_id}`
   3. `GET /batches`
   4. `POST /batches/{batch_id}/cancel`
1. **Attributes coverage: 5/33 = 15.1%**

| Original attribute     | Source                           | Mapped to attribute(s)                   | Specification type | Note                                                |
|------------------------|----------------------------------|------------------------------------------|--------------------|-----------------------------------------------------|
| `batch_id`             | path-params:list,retrieve,cancel | ❌                                        |                    |                                                     |
| `completion_window`    | req:create                       | `gen_ai.request.batch.completion_window` | Custom             | Attempted GenAI, undefined by spec                  |
| `endpoint`             | req:create                       | `gen_ai.request.batch.endpoint`          | Custom             | Attempted GenAI, undefined by spec                  |
| `input_file_id`        | req:create                       | `gen_ai.request.batch.input_file_id`     | Custom             | Attempted GenAI, undefined by spec                  |
| `metadata`             | req:create                       | ❌                                        |                    |                                                     |
| `output_expires_after` | req:create                       | ❌                                        |                    |                                                     |
| `after`                | req:list (query)                 | ❌                                        |                    |                                                     |
| `limit`                | req:list (query)                 | ❌                                        |                    |                                                     |
| `id`                   | resp:`Batch`                     | `gen_ai.response.batch.id`               | Custom             | Attempted GenAI, undefined by spec                  |
| `object`               | resp:`Batch`, resp:list          | ❌                                        |                    | Always `"batch"` / `"list"` — low information value |
| `endpoint`             | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `completion_window`    | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `status`               | resp:`Batch`                     | `gen_ai.response.batch.status`           | Custom             | Attempted GenAI, undefined by spec                  |
| `input_file_id`        | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `output_file_id`       | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `error_file_id`        | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `created_at`           | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `in_progress_at`       | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `expires_at`           | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `finalizing_at`        | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `completed_at`         | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `failed_at`            | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `expired_at`           | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `cancelling_at`        | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `cancelled_at`         | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `request_counts`       | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `errors`               | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `metadata`             | resp:`Batch`                     | ❌                                        |                    |                                                     |
| `model`                | resp:`Batch`                     | ❌                                        |                    | OTel `gen_ai.response.model` would fit              |
| `usage`                | resp:`Batch`                     | ❌                                        |                    | OTel `gen_ai.usage.{input,output}_tokens` would fit |
| `data`                 | resp:list                        | ❌                                        |                    | List envelope; list count not emitted               |
| `has_more`             | resp:list                        | ❌                                        |                    |                                                     |
| `first_id`             | resp:list                        | ❌                                        |                    |                                                     |
| `last_id`              | resp:list                        | ❌                                        |                    |                                                     |

### 3. `ConversationsOpenAIApiEndpointHandler`

Info:
1. Request type: JSON (`POST /conversations`, `POST /conversations/{id}` for the listed UPDATE route)
1. Response type: JSON (`Conversation` object, or `ConversationDeletedResource` for delete)
1. Covers endpoints:
   1. `POST /conversations`
   2. `GET /conversations/{conversation_id}`
   3. `POST /conversations/{conversation_id}` — **not implemented** (UPDATE; the dispatcher has no route for it)
   4. `DELETE /conversations/{conversation_id}`

   *Bonus (not in the listed scope):* `POST/GET /conversations/{id}/items` and `GET/DELETE /conversations/{id}/items/{item_id}` — handled by the four `items.*` route handlers.
1. **Attributes coverage: 3/7 = 42.8%** (denominator excludes items routes; UPDATE is counted as not-traced)

| Original attribute | Source                      | Mapped to attribute(s)          | Specification type | Note                                                                |
|--------------------|-----------------------------|---------------------------------|--------------------|---------------------------------------------------------------------|
| `items`            | req:create                  | ❌                               |                    | Initial conversation items not parsed                               |
| `metadata`         | req:create,update           | ❌                               |                    | UPDATE route is not implemented at all — see ConversationRoute enum |
| `id`               | resp:all                    | `gen_ai.conversation.id`        | Custom             | Attempted GenAI, undefined by spec                                  |
| `object`           | resp:all                    | ❌                               |                    | Always `"conversation"` / `"conversation.deleted"`                  |
| `created_at`       | resp:create,retrieve,update | `tracy.conversation.created_at` | Custom             |                                                                     |
| `metadata`         | resp:create,retrieve,update | ❌                               |                    |                                                                     |
| `deleted`          | resp:delete                 | `tracy.conversation.deleted`    | Custom             |                                                                     |

### 4. `FilesOpenAIApiEndpointHandler`

Info:
1. Request type: `multipart/form-data` (`POST /files`); query params for `GET /files`; path params for the rest
1. Response type: JSON (`File` object) for `list/create/retrieve/delete`; **binary** for `GET /files/{id}/content`
1. Covers endpoints:
   1. `GET /files`
   2. `POST /files`
   3. `GET /files/{file_id}`
   4. `DELETE /files/{file_id}`
   5. `GET /files/{file_id}/content`
1. **Attributes coverage: 16/21 = 76.1%**

| Original attribute | Source                                       | Mapped to attribute(s)                                                          | Specification type | Note                                            |
|--------------------|----------------------------------------------|---------------------------------------------------------------------------------|--------------------|-------------------------------------------------|
| `file`             | req:create (multipart)                       | `tracy.request.file.filename`,</br>`tracy.request.file.size_bytes`              | Custom             |                                                 |
| `purpose`          | req:create (multipart),</br>req:list (query) | `tracy.request.purpose`                                                         | Custom             | Same key reused for both `create` and `list`    |
| `expires_after`    | req:create (multipart)                       | `tracy.request.expires_after.anchor`,</br>`tracy.request.expires_after.seconds` | Custom             | JSON object decoded out of multipart text part  |
| `after`            | req:list (query)                             | `tracy.request.after`                                                           | Custom             |                                                 |
| `limit`            | req:list (query)                             | `tracy.request.limit`                                                           | Custom             |                                                 |
| `order`            | req:list (query)                             | `tracy.request.order`                                                           | Custom             |                                                 |
| `id`               | resp:`File`                                  | `tracy.response.file.id`                                                        | Custom             |                                                 |
| `object`           | resp:`File`,</br>resp:list,</br>resp:delete  | ❌                                                                               |                    | Always `"file"` / `"list"`                      |
| `bytes`            | resp:`File`                                  | `tracy.response.file.size_bytes`                                                | Custom             | Renamed `bytes` → `size_bytes` for clarity      |
| `created_at`       | resp:`File`                                  | `tracy.response.file.created_at`                                                | Custom             |                                                 |
| `filename`         | resp:`File`                                  | `tracy.response.file.filename`                                                  | Custom             |                                                 |
| `purpose`          | resp:`File`                                  | `tracy.response.file.purpose`                                                   | Custom             |                                                 |
| `status`           | resp:`File`                                  | `tracy.response.file.status`                                                    | Custom             | Spec marks `status` as deprecated               |
| `expires_at`       | resp:`File`                                  | `tracy.response.file.expires_at`                                                | Custom             |                                                 |
| `status_details`   | resp:`File`                                  | ❌                                                                               |                    | Spec marks deprecated                           |
| `data`             | resp:list                                    | `tracy.response.list.count`                                                     | Custom             | Length only — items not enumerated              |
| `has_more`         | resp:list                                    | ❌                                                                               |                    |                                                 |
| `first_id`         | resp:list                                    | ❌                                                                               |                    |                                                 |
| `last_id`          | resp:list                                    | ❌                                                                               |                    |                                                 |
| `deleted`          | resp:delete                                  | `tracy.response.deleted`                                                        | Custom             |                                                 |
| (binary body)      | resp:content                                 | `tracy.response.file.size_bytes` (from `Content-Length` header)                 | Custom             | Body itself not captured                        |

### 5. `ModelsOpenAIApiEndpointHandler`

Info:
1. Request type: none (path params only)
1. Response type: JSON (`Model` object; list envelope for `GET /models`)
1. Covers endpoints:
   1. `GET /models`
   2. `GET /models/{model}`
   3. `DELETE /models/{model}` — **not implemented** (handler's `deriveOperationName` only knows `models.list` / `models.retrieve`; DELETE falls through to `models.list` by default)
1. **Attributes coverage: 4/6 = 67%**

| Original attribute | Source                       | Mapped to attribute(s)    | Specification type | Note                                                |
|--------------------|------------------------------|---------------------------|--------------------|-----------------------------------------------------|
| `id`               | resp:`Model`                 | `tracy.response.model.id` | Custom             | OTel `gen_ai.response.model` would also fit         |
| `object`           | resp:`Model`,</br>resp:list  | `tracy.response.object`   | Custom             | Always `"model"` / `"list"` — but emitted           |
| `created`          | resp:`Model`                 | `tracy.response.created`  | Custom             |                                                     |
| `owned_by`         | resp:`Model`                 | `tracy.response.owned_by` | Custom             |                                                     |
| `data`             | resp:list                    | ❌                         |                    | List count not emitted                              |
| `deleted`          | resp:delete                  | ❌                         |                    | DELETE route is not dispatched by the handler — gap |

The path-derived `model` (last URL segment when present) is recorded as `gen_ai.request.model` (GenAI). It is not counted in the table because it is a routing/path concern rather than a documented body field, matching the convention used in the Audio entry.

### 6. `ModerationsOpenAIApiEndpointHandler`

Info:
1. Request type: JSON
1. Response type: JSON
1. Covers endpoints:
   1. `POST /moderations`
1. **Attributes coverage: 4/9 = 44.4%**

| Original attribute             | Source                | Mapped to attribute(s)                        | Specification type | Note                                                                   |
|--------------------------------|-----------------------|-----------------------------------------------|--------------------|------------------------------------------------------------------------|
| `input`                        | req                   | `type(input)` -> `tracy.request.input.type`   | Custom             | Only the **type** (`"string"` / `"array"`) — content itself not traced |
| `model`                        | req                   | ❌                                             |                    |                                                                        |
| `id`                           | resp                  | ❌                                             |                    |                                                                        |
| `model`                        | resp                  | ❌                                             |                    | OTel `gen_ai.response.model` would fit                                 |
| `results`                      | resp:`Moderation[]`   | `tracy.response.results.count`                | Custom             |                                                                        |
| `flagged`                      | resp:results[0]       | `tracy.response.results.flagged`              | Custom             | First element only                                                     |
| `categories`                   | resp:results[0]       | `tracy.response.results.categories`           | Custom             | First element only; serialised as JSON string                          |
| `category_scores`              | resp:results[0]       | `tracy.response.results.category_scores`      | Custom             | First element only; serialised as JSON string                          |
| `category_applied_input_types` | resp:results[0]       | ❌                                             |                    |                                                                        |

Notes:
1. `input` is either a string or an array of strings/objects; it is NOT traced, only its type is (string/array).
1. Only the first `results[0]` is traced. Probably, AI didn't know how to handle arrays of inputs with intermediate indexing in the attribute name.
   Yet several entries are possible depending on the input type.

---

**Cross-cutting attributes** — every OpenAI handler in this audit (including audio) emits the following on **every** span; they do not correspond to documented OpenAI request/response **fields** and so are not counted toward per-handler coverage percentages, mirroring the convention used for the Audio entry:

- `gen_ai.provider.name = "openai"` *(GenAI semconv)*
- `server.address`, `server.port` *(GenAI semconv via OTel HTTP semconv)*
- `gen_ai.operation.name` *(GenAI; URL-derived per handler — see each handler's source)*
- `openai.api.type` *(Custom; one of `audio` / `batches` / `conversations` / `files` / `models` / `moderations`)*
- `http.response.status_code` *(OTel HTTP semconv)*

### 7. `ResponsesOpenAIApiEndpointHandler` — modifications since `6e028bd2`

Info:
1. Request type: JSON
1. Response type: JSON (non-streaming) or `text/event-stream` (streaming)
1. Covers endpoints (URL-derived `gen_ai.operation.name`):
   1. `POST /responses`, `GET /responses/{response_id}` → `generate_content`
   2. `POST /responses/{response_id}/cancel` → `response.cancel`
   3. `GET /responses/{response_id}/input_tokens` → `response.input_tokens.count`

This entry lists **only attributes whose tracing was added or fixed by this patch**. Attributes already traced before `6e028bd2` — `model`, `temperature`, `top_p`, `max_output_tokens`, `truncation`, `parallel_tool_calls`, `stream`, `response_format`, `tool_choice`, `reasoning` (whole JSON), `text`, `previous_response_id`, `instructions`, `input`, `tools`, `id`, `object` (formerly mis-mapped to `gen_ai.operation.name`), `model`, `output`, `usage.{input,output}_tokens` — are unchanged and are not repeated here.

| New / fixed attribute   | Source                       | Mapped to attribute(s)                         | Specification type | Note                                                                                                                                                              |
|-------------------------|------------------------------|------------------------------------------------|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `reasoning.effort`      | req                          | `tracy.request.reasoning.effort`               | Custom             | NEW. Sub-field of `reasoning` (previously only the whole `reasoning` JSON was traced).                                                                            |
| `reasoning.summary`     | req                          | `tracy.request.reasoning.summary`              | Custom             | NEW.                                                                                                                                                              |
| `store`                 | req                          | `gen_ai.request.store` + `tracy.request.store` | GenAI + Custom     | Mirrored under `tracy.*` in addition to the existing `gen_ai.*` mapping.                                                                                          |
| `object`                | resp                         | `tracy.response.object`                        | Custom             | FIXED. Previously read by `setCommonResponseAttributes` into `gen_ai.operation.name` (incorrect for `cancel` / `input_tokens` routes). Now URL-derived, see below. |
| `status`                | resp                         | `tracy.response.status`                        | Custom             | NEW. `completed` / `incomplete` / `failed`.                                                                                                                       |
| `background`            | resp                         | `tracy.response.background`                    | Custom             | NEW.                                                                                                                                                              |
| `store`                 | resp                         | `tracy.response.store`                         | Custom             | NEW.                                                                                                                                                              |
| `created_at`            | resp                         | `tracy.response.created_at`                    | Custom             | NEW.                                                                                                                                                              |
| `completed_at`          | resp:SSE only                | `tracy.response.completed_at`                  | Custom             | NEW. Only emitted from the `response.completed` SSE event.                                                                                                        |
| `input_tokens`          | resp:`/input_tokens` route   | `gen_ai.usage.input_tokens`                    | GenAI              | NEW. The input-token-counting endpoint returns `input_tokens` at top level (not nested under `usage`).                                                            |

Streaming (SSE) — events handled by the patched `handleStreaming`:

| SSE event                   | Before | After  | Attributes set on the span                                                                                                                                  |
|-----------------------------|:------:|:------:|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `response.output_text.done` | ✅     | ✅     | `gen_ai.completion.0.{content,finish_reason}` (unchanged)                                                                                                   |
| `response.completed`        | ❌     | ✅ NEW | `gen_ai.response.{id,model}`, `tracy.response.{object,status,created_at,completed_at}`, `gen_ai.usage.{input,output}_tokens`, `http.response.status_code=200` |

Operation-name dispatch (NEW; URL-derived on both request and response, overrides the value `setCommonResponseAttributes` reads from the response body's `object` field):

| URL path segment contains | `gen_ai.operation.name`        |
|---------------------------|--------------------------------|
| `input_tokens`            | `response.input_tokens.count`  |
| `cancel`                  | `response.cancel`              |
| (otherwise)               | `generate_content`             |

Cross-cutting attributes added by this patch — `gen_ai.provider.name="openai"`, `server.address`, `server.port`, `openai.api.type="responses"`, `http.response.status_code` — match the convention listed at the bottom of the OpenAI section.

**Coverage delta** (counts only newly traced documented OpenAI fields; cross-cutting attributes excluded):

|                        | Before `6e028bd2` | After (current HEAD) | Δ                                                                                              |
|------------------------|:-----------------:|:--------------------:|-------------------------------------------------------------------------------------------------|
| Request fields traced  | 16                | 18                   | +2 (`reasoning.effort`, `reasoning.summary`)                                                    |
| Response fields traced | 5                 | 11                   | +6 (`status`, `background`, `store`, `created_at`, `completed_at`, top-level `input_tokens`)    |
| SSE events handled     | 1                 | 2                    | +1 (`response.completed`)                                                                       |

Notes:
1. The `object` field was technically "traced" before the patch via `setCommonResponseAttributes`, but mapped to the wrong attribute (`gen_ai.operation.name`). The patch keeps it traced under the semantically correct `tracy.response.object` and replaces `gen_ai.operation.name` with a URL-derived value. Listed under "fixed", not "new".
2. The denominator above is each documented Responses-API top-level field counted once. Nested fields (`usage.input_tokens`, `usage.output_tokens`, `reasoning.effort`, `reasoning.summary`) are counted as separate items only when the patch adds tracing for them specifically.


## Anthropic

> **Spec-type rule.** A `Specification type` of **GenAI** here means the attribute name appears in the OTel GenAI registry at <https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai>. Attributes with a `gen_ai.` prefix that are **not** in that registry (indexed `gen_ai.prompt.{i}.*` / `gen_ai.completion.{i}.*` / `gen_ai.tool.{i}.*`, sub-namespaces such as `gen_ai.response.batch.*`, `gen_ai.response.model.*`, `gen_ai.response.list.*`, `gen_ai.response.file.*`, `gen_ai.metadata.user_id`, `gen_ai.usage.service_tier`, `gen_ai.response.role`, etc.) are flagged as **non-conventional GenAI**. The `tracy.*` and provider-specific (`anthropic.*`) namespaces are **Custom**.

### 8. `AnthropicCountTokensHandler`

Info:
1. Request type: JSON
1. Response type: JSON
1. Covers endpoints:
   1. `POST /v1/messages/count_tokens`
1. **Attributes coverage: 2/9 = 22.2%**

Special case: the count-tokens response body has no `id` field, so `gen_ai.response.id` is mapped from the `x-request-id` HTTP response header instead. This is not counted toward the documented-field coverage above (it's a header, not a body field).

| Original attribute            | Source | Mapped to attribute(s)      | Specification type | Note                                                                |
|-------------------------------|--------|-----------------------------|--------------------|---------------------------------------------------------------------|
| `model`                       | req    | `gen_ai.request.model`      | GenAI              |                                                                     |
| `messages`                    | req    | ❌                           |                    | Spec defines `gen_ai.input.messages` — would fit                    |
| `system`                      | req    | ❌                           |                    | Spec defines `gen_ai.system_instructions` — would fit               |
| `tools`                       | req    | ❌                           |                    | Spec defines `gen_ai.tool.definitions` — would fit                  |
| `tool_choice`                 | req    | ❌                           |                    |                                                                     |
| `thinking`                    | req    | ❌                           |                    |                                                                     |
| `output_config`               | req    | ❌                           |                    |                                                                     |
| `cache_control`               | req    | ❌                           |                    |                                                                     |
| `input_tokens`                | resp   | `gen_ai.usage.input_tokens` | GenAI              |                                                                     |



### 9. `AnthropicListEndpointHandler`

> **Design note.** This single handler dispatches across three distinct endpoint classes — **batches, models, files** — by inspecting URL path segments. The three classes share only a generic list envelope (`data`, `first_id`, `last_id`, `has_more`); their resource objects (`MessageBatch`, `ModelInfo`, `FileMetadata`) are otherwise unrelated. One handler per endpoint class, with the list-envelope logic factored into a small shared helper, would be easier to extend (e.g. when Anthropic adds a new resource), easier to test in isolation, and would have avoided the two latent bugs flagged below. The current `if (detectedType == "models") … if (body["type"] == "model") …` cascade in a single 200-line handler is a poor structural choice.

> **Two latent bugs caused by the conflated dispatcher (worth fixing in a follow-up):**
> 1. **`capabilities.vision` typo** — for `models.retrieve` the handler reads `capabilities.vision`, which is **not** a documented capability key in the Anthropic Models API. The actual key is `capabilities.image_input`. The corresponding span attribute `gen_ai.response.model.capabilities.vision` is therefore silently never set.
> 2. **`deleted: true` check on a response that lacks the field** — for `files.delete` the handler does `if (body["deleted"]?.booleanOrNull == true) { … }` to set `gen_ai.output.type="file_deleted"` and `gen_ai.response.file.id`. But the Anthropic DELETE response is `{id, type: "file_deleted"}` — there is no `deleted` field. The branch is dead code; the file-delete span never sets the output type. The check should be `body["type"]?.jsonPrimitive?.content == "file_deleted"`.

> **Operation-name collision.** The `gen_ai.operation.name` for `GET /v1/files/{id}/content` and `GET /v1/messages/batches/{id}/results` both collapse into `<type>.retrieve` (because `lastSegment ("content" | "results") != detectedType`), so a content-download span is indistinguishable from a metadata-retrieve span on the same resource. Same problem for batch `.../results`.

Info:
1. Request type: query parameters (GET list); none for retrieve/delete/cancel; multipart for `POST /v1/files`
1. Response type: JSON for all routes; binary for `GET /v1/files/{file_id}/content` and `GET /v1/messages/batches/{id}/results` (the latter is a JSONL stream, not parsed)
1. Covers endpoints (13 total):
   - **Batches (6):** `GET /v1/messages/batches`, `POST /v1/messages/batches`, `GET /v1/messages/batches/{id}`, `POST /v1/messages/batches/{id}/cancel`, `DELETE /v1/messages/batches/{id}`, `GET /v1/messages/batches/{id}/results`
   - **Models (2):** `GET /v1/models`, `GET /v1/models/{model_id}`
   - **Files (5):** `POST /v1/files`, `GET /v1/files`, `GET /v1/files/{id}`, `DELETE /v1/files/{id}`, `GET /v1/files/{id}/content`
1. **Combined attributes coverage: 34/50 = 68%** (each documented field counted once across the 13 routes).

#### 9.1 Batches — coverage 15/24 = 63%

| Original attribute                     | Source                            | Mapped to attribute(s)                                       | Specification type      | Note                                                |
|----------------------------------------|-----------------------------------|--------------------------------------------------------------|-------------------------|-----------------------------------------------------|
| `requests`                             | req:create                        | `gen_ai.request.batch.size` (length only)                    | non-conventional GenAI  | Per-request bodies inside `requests[]` are not parsed |
| `after_id`                             | req:list (query)                  | ❌                                                            |                         |                                                     |
| `before_id`                            | req:list (query)                  | ❌                                                            |                         |                                                     |
| `limit`                                | req:list (query)                  | ❌                                                            |                         |                                                     |
| `id`                                   | resp:`MessageBatch`               | `gen_ai.response.batch.id`                                   | non-conventional GenAI  |                                                     |
| `type`                                 | resp:`MessageBatch`               | `gen_ai.output.type` (when `"message_batch"`)                | GenAI                   |                                                     |
| `processing_status`                    | resp:`MessageBatch`               | `gen_ai.response.batch.processing_status`                    | non-conventional GenAI  |                                                     |
| `created_at`                           | resp:`MessageBatch`               | `gen_ai.response.batch.created_at`                           | non-conventional GenAI  |                                                     |
| `expires_at`                           | resp:`MessageBatch`               | `gen_ai.response.batch.expires_at`                           | non-conventional GenAI  |                                                     |
| `request_counts.processing`            | resp:`MessageBatch`               | `gen_ai.response.batch.request_counts.processing`            | non-conventional GenAI  |                                                     |
| `request_counts.succeeded`             | resp:`MessageBatch`               | `gen_ai.response.batch.request_counts.succeeded`             | non-conventional GenAI  |                                                     |
| `request_counts.errored`               | resp:`MessageBatch`               | `gen_ai.response.batch.request_counts.errored`               | non-conventional GenAI  |                                                     |
| `request_counts.canceled`              | resp:`MessageBatch`               | `gen_ai.response.batch.request_counts.canceled`              | non-conventional GenAI  |                                                     |
| `request_counts.expired`               | resp:`MessageBatch`               | `gen_ai.response.batch.request_counts.expired`               | non-conventional GenAI  |                                                     |
| `archived_at`                          | resp:`MessageBatch`               | ❌                                                            |                         |                                                     |
| `cancel_initiated_at`                  | resp:`MessageBatch`               | ❌                                                            |                         |                                                     |
| `ended_at`                             | resp:`MessageBatch`               | ❌                                                            |                         |                                                     |
| `results_url`                          | resp:`MessageBatch`               | ❌                                                            |                         |                                                     |
| `data` (envelope)                      | resp:list                         | `gen_ai.response.list.count`                                 | non-conventional GenAI  | Length only                                         |
| `has_more` (envelope)                  | resp:list                         | `gen_ai.response.list.has_more`                              | non-conventional GenAI  |                                                     |
| `first_id` (envelope)                  | resp:list                         | `gen_ai.response.list.first_id`                              | non-conventional GenAI  |                                                     |
| `last_id` (envelope)                   | resp:list                         | `gen_ai.response.list.last_id`                               | non-conventional GenAI  |                                                     |
| (binary `.jsonl`)                      | resp:`/results` route             | ❌                                                            |                         | Operation name collides with `batches.retrieve`     |
| error path body                        | resp:4xx                          | `error.type` (from `error.type` JSON field)                  | OTel `error.type`       | All 4xx routes                                      |

#### 9.2 Models — coverage 9/13 = 69%

| Original attribute                  | Source                | Mapped to attribute(s)                              | Specification type      | Note                                                                 |
|-------------------------------------|-----------------------|-----------------------------------------------------|-------------------------|----------------------------------------------------------------------|
| `after_id` / `before_id` / `limit`  | req:list (query)      | ❌                                                   |                         |                                                                      |
| path `model_id`                     | req:retrieve          | `gen_ai.request.model`                              | GenAI                   | Last URL segment when present                                        |
| `id`                                | resp:`ModelInfo`      | `gen_ai.response.model` + `gen_ai.response.model.id` | GenAI + non-conv. GenAI |                                                                      |
| `type`                              | resp:`ModelInfo`      | `gen_ai.output.type` (when `"model"`)               | GenAI                   |                                                                      |
| `display_name`                      | resp:`ModelInfo`      | `gen_ai.response.model.display_name`                | non-conventional GenAI  |                                                                      |
| `max_input_tokens`                  | resp:`ModelInfo`      | `gen_ai.response.model.max_input_tokens`            | non-conventional GenAI  |                                                                      |
| `max_tokens`                        | resp:`ModelInfo`      | `gen_ai.response.model.max_output_tokens`           | non-conventional GenAI  | Renamed `max_tokens` → `max_output_tokens` (handler-side)            |
| `created_at`                        | resp:`ModelInfo`      | `gen_ai.response.model.created_at`                  | non-conventional GenAI  |                                                                      |
| `capabilities.batch.supported`      | resp:`ModelInfo`      | `gen_ai.response.model.capabilities.batch`          | non-conventional GenAI  | Reads top-level `capabilities.batch`, not the nested `.supported`    |
| `capabilities.citations.supported`  | resp:`ModelInfo`      | `gen_ai.response.model.capabilities.citations`      | non-conventional GenAI  | Same shape mismatch as above                                         |
| `capabilities.image_input.supported`| resp:`ModelInfo`      | ❌ (handler reads `capabilities.vision` — **bug**)   |                         | `vision` is not a documented key; spans never get this attribute set |
| `capabilities.{code_execution,context_management,effort,pdf_input,structured_outputs,thinking}` | resp:`ModelInfo` | ❌ |                       | Not parsed                                                          |
| list envelope (`data`, `has_more`, `first_id`, `last_id`) | resp:list | `gen_ai.response.list.{count,has_more,first_id,last_id}` | non-conventional GenAI  | Same envelope helpers as Batches                                     |

#### 9.3 Files — coverage 10/13 = 77%

| Original attribute                                          | Source                       | Mapped to attribute(s)                                                                      | Specification type     | Note                                                                                                |
|-------------------------------------------------------------|------------------------------|---------------------------------------------------------------------------------------------|------------------------|-----------------------------------------------------------------------------------------------------|
| `file` (multipart part)                                     | req:upload (multipart)       | `gen_ai.request.file.filename`,</br>`gen_ai.request.file.mime_type`,</br>`gen_ai.request.file.size_bytes` | non-conventional GenAI |                                                                                                     |
| `after_id` / `before_id` / `limit` / `scope_id`             | req:list (query)             | ❌                                                                                           |                        |                                                                                                     |
| `id`                                                        | resp:`FileMetadata`,</br>resp:DELETE | `gen_ai.response.file.id`                                                            | non-conventional GenAI |                                                                                                     |
| `type`                                                      | resp:`FileMetadata`,</br>resp:DELETE | `gen_ai.output.type` (`"file"` or `"file_deleted"`)                                  | GenAI                  | `file_deleted` branch is **dead code** — checks `body["deleted"]` which the API never returns       |
| `created_at`                                                | resp:`FileMetadata`          | `gen_ai.response.file.created_at`                                                           | non-conventional GenAI |                                                                                                     |
| `filename`                                                  | resp:`FileMetadata`          | `gen_ai.response.file.filename`                                                             | non-conventional GenAI |                                                                                                     |
| `mime_type`                                                 | resp:`FileMetadata`          | `gen_ai.response.file.mime_type`                                                            | non-conventional GenAI | Falls back to `media_type` if `mime_type` is missing                                                |
| `size_bytes`                                                | resp:`FileMetadata`          | `gen_ai.response.file.size_bytes`                                                           | non-conventional GenAI |                                                                                                     |
| `downloadable`                                              | resp:`FileMetadata`          | `gen_ai.response.file.downloadable`                                                         | non-conventional GenAI |                                                                                                     |
| `scope`                                                     | resp:`FileMetadata`          | ❌                                                                                           |                        |                                                                                                     |
| list envelope (`data`, `has_more`, `first_id`, `last_id`)   | resp:list                    | `gen_ai.response.list.{count,has_more,first_id,last_id}`                                    | non-conventional GenAI |                                                                                                     |
| (binary body)                                               | resp:content                 | ❌                                                                                           |                        | `Content-Length` not captured; `gen_ai.operation.name` collides with `files.retrieve`               |

### 10. `AnthropicMessagesHandler`

> **Provenance.** This handler was originally a **manual** implementation inlined in `AnthropicLLMTracingAdapter`; this round of changes refactored it into its own handler (`handlerFor` in `AnthropicLLMTracingAdapter` now dispatches to it). Its attribute coverage is materially higher than the breeder-generated handlers because it is hand-written — keep this in mind when comparing percentages across handlers.

Info:
1. Request type: JSON
1. Response type: JSON (non-streaming) or `text/event-stream` (streaming, `?stream=true` or `stream:true` in body)
1. Covers endpoints:
   1. `POST /v1/messages`
1. **Attributes coverage: 20/30 = 67%** (request 11/19 = 58% · response 9/11 = 82% · SSE: 2 of ~14 documented event types handled — the two most informative, `message_start` and `message_delta`)

#### 10.1 Request

| Original attribute     | Source | Mapped to attribute(s)                                                                                            | Specification type                | Note                                                                                                |
|------------------------|--------|-------------------------------------------------------------------------------------------------------------------|-----------------------------------|-----------------------------------------------------------------------------------------------------|
| `model`                | req    | `gen_ai.request.model`                                                                                            | GenAI                             |                                                                                                     |
| `messages`             | req    | `gen_ai.prompt.{i}.role`,</br>`gen_ai.prompt.{i}.content` (full content stringified)                              | non-conventional GenAI            | Registry has `gen_ai.input.messages` (not indexed); handler emits the older indexed pattern         |
| `max_tokens`           | req    | `gen_ai.request.max_tokens`                                                                                       | GenAI                             |                                                                                                     |
| `system`               | req    | `gen_ai.prompt.system.content` (string form),</br>`gen_ai.prompt.system.{i}.{type,content}` (array form)          | non-conventional GenAI            | Registry has `gen_ai.system_instructions`                                                           |
| `temperature`          | req    | `gen_ai.request.temperature`                                                                                      | GenAI                             |                                                                                                     |
| `top_k`                | req    | `gen_ai.request.top_k`                                                                                            | GenAI                             |                                                                                                     |
| `top_p`                | req    | `gen_ai.request.top_p`                                                                                            | GenAI                             |                                                                                                     |
| `tools`                | req    | `gen_ai.tool.{i}.{name,description,type,parameters}`                                                              | non-conventional GenAI            | Registry has flat `gen_ai.tool.{name,description,type}` (no indexing); `parameters` is undefined    |
| `metadata.user_id`     | req    | `gen_ai.metadata.user_id`                                                                                         | non-conventional GenAI            |                                                                                                     |
| `service_tier`         | req    | `gen_ai.usage.service_tier`                                                                                       | non-conventional GenAI            | Note: registry has `gen_ai.openai.request.service_tier` for OpenAI-specific case, no Anthropic one  |
| Image / document parts | req    | `tracy.input.media.{i}.{type,field,content_type,...}` (via `MediaContentExtractor`)                              | Custom                            | Base64 / URL / nested content blocks                                                                |
| `tool_choice`          | req    | ❌                                                                                                                 |                                   |                                                                                                     |
| `stream`               | req    | ❌                                                                                                                 |                                   | Spec defines `gen_ai.request.stream` — would fit                                                    |
| `stop_sequences`       | req    | ❌                                                                                                                 |                                   | Spec defines `gen_ai.request.stop_sequences` — would fit                                            |
| `thinking`             | req    | ❌                                                                                                                 |                                   |                                                                                                     |
| `container`            | req    | ❌                                                                                                                 |                                   |                                                                                                     |
| `inference_geo`        | req    | ❌                                                                                                                 |                                   |                                                                                                     |
| `cache_control`        | req    | ❌                                                                                                                 |                                   |                                                                                                     |
| `output_config`        | req    | ❌                                                                                                                 |                                   |                                                                                                     |
| `mcp_servers`          | req    | ❌                                                                                                                 |                                   |                                                                                                     |

#### 10.2 Response

| Original attribute                  | Source                              | Mapped to attribute(s)                                                                                          | Specification type      | Note                                                                                  |
|-------------------------------------|-------------------------------------|-----------------------------------------------------------------------------------------------------------------|-------------------------|---------------------------------------------------------------------------------------|
| `id`                                | resp                                | `gen_ai.response.id`                                                                                            | GenAI                   |                                                                                       |
| `type`                              | resp                                | `gen_ai.output.type`                                                                                            | GenAI                   |                                                                                       |
| `role`                              | resp                                | `gen_ai.response.role`                                                                                          | non-conventional GenAI  | Not in registry                                                                       |
| `model`                             | resp                                | `gen_ai.response.model`                                                                                         | GenAI                   |                                                                                       |
| `content[i].type` (= "text")        | resp                                | `gen_ai.completion.{i}.type`,</br>`gen_ai.completion.{i}.content`                                               | non-conventional GenAI  | Registry has `gen_ai.output.messages`; handler emits indexed legacy pattern           |
| `content[i].type` (= "tool_use")    | resp                                | `gen_ai.completion.{i}.tool.call.id`,</br>`gen_ai.completion.{i}.tool.call.type`,</br>`gen_ai.completion.{i}.tool.name`,</br>`gen_ai.completion.{i}.tool.arguments` | non-conventional GenAI  | Registry has flat `gen_ai.tool.call.{id,arguments}` (no indexing)                     |
| `stop_reason`                       | resp                                | `gen_ai.response.finish_reasons` (single-element list)                                                          | GenAI                   |                                                                                       |
| `usage.input_tokens`                | resp                                | `gen_ai.usage.input_tokens`                                                                                     | GenAI                   |                                                                                       |
| `usage.output_tokens`               | resp                                | `gen_ai.usage.output_tokens`                                                                                    | GenAI                   |                                                                                       |
| `usage.cache_creation_input_tokens` | resp                                | `gen_ai.usage.cache_creation.input_tokens`                                                                      | GenAI                   |                                                                                       |
| `usage.cache_read_input_tokens`     | resp                                | `gen_ai.usage.cache_read.input_tokens`                                                                          | GenAI                   |                                                                                       |
| `usage.service_tier`                | resp                                | `gen_ai.usage.service_tier`                                                                                     | non-conventional GenAI  |                                                                                       |
| `stop_sequence`                     | resp                                | ❌                                                                                                               |                         |                                                                                       |
| `thinking`                          | resp                                | ❌                                                                                                               |                         |                                                                                       |

#### 10.3 Streaming SSE events handled

| SSE event                       | Handled | Attributes set                                                                                                          | Note                                            |
|---------------------------------|:-------:|-------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------|
| `message_start`                 | ✅      | `gen_ai.response.{id,model,role}`,</br>`gen_ai.output.type`,</br>`gen_ai.usage.input_tokens`                            |                                                 |
| `message_delta`                 | ✅      | `gen_ai.usage.output_tokens`                                                                                            |                                                 |
| `message_stop`                  | ❌      | —                                                                                                                       | Could carry final usage stats                   |
| `content_block_{start,delta,stop}` | ❌  | —                                                                                                                       | Per-block text accumulation not implemented     |
| `ping`                          | ❌      | —                                                                                                                       | (informational; safely skippable)               |
| `error`                         | ❌      | —                                                                                                                       | Span ERROR status not set from the SSE branch   |

---

**Cross-cutting attributes** — every Anthropic handler in this audit emits the following on **every** span; they do not correspond to documented Anthropic request/response **fields** and are not counted toward per-handler coverage percentages, mirroring the OpenAI convention:

- `gen_ai.provider.name = "anthropic"` *(GenAI)*
- `server.address`, `server.port` *(OTel HTTP semconv)*
- `gen_ai.operation.name` *(GenAI; URL-derived per handler)*
- `anthropic.api.type` *(Custom; one of `count_tokens` / `batches` / `models` / `files` / `messages`)*
- `http.response.status_code` *(OTel HTTP semconv)*


## Gemini

1. GeminiCachedContentsHandler (caching)
1. GeminiContentGenHandler (implement streaming for `generateContentStreaming`)
1. GeminiEmbeddingsHandler (embedding)
1. GeminiModelsHandler (models)

