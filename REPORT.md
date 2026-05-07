
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

Updated handlers:
1. ResponsesOpenAIApiEndpointHandler – which new attributes are covered after modifications, by how much coverage increased.


## Anthropic

1. AnthropicCountTokensHandler (count message tokens)
1. AnthropicListEndpointHandler (batches/files/models)
1. AnthropicMessagesHandler (messages) (refactored endpoint handler -> moved logic in specific handler)


## Gemini

1. GeminiCachedContentsHandler (caching)
1. GeminiContentGenHandler (implement streaming for `generateContentStreaming`)
1. GeminiEmbeddingsHandler (embedding)
1. GeminiModelsHandler (models)

