
# Summary

**Verdict:** AI managed to generate working handlers' boilerplates for across all providers (yet, Gemini is the weakest in terms of coverage and code quality due to non-traditional endpoint routing via route suffixes, not REST). However, the sets of covered attributes for the newly supported endpoints are very modest and there is a lack of tests for the created handlers. **Overall, this solution is about 65–70% of a real modification that would constitute full support of new handlers for all providers.**

**Weak points:**
1. Lack of tests for the generated handlers: fixable via prompt → fix: explicitly request AI to generate tests.
2. Many request/response fields remain uncovered for all newly supported endpoints → 
   fix: assuming the AI agent is search web during evaluation, pass links to the API reference for all providers.
3. Some implemented handlers contain code smells; e.g., `AnthropicListEndpointHandler` handles three non-related routes at once → maintainability issue (better to split them into three handlers with shared functionality).


**All the weak points can be addressed via prompts alone.**


## Newly supported endpoints

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
| Gemini    | cachedContents | `/v1beta/cachedContents`                           | ✅   | `org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiCachedContentsHandler`                         | `GET` (list) — only LIST response is parsed. `POST` (create), `PATCH`, `DELETE` are dispatched to this handler too but their bodies are **not** parsed — gap |
|           |                | `/v1beta/cachedContents/{name}`                    |     |                                                                                                       | `GET` (get), `PATCH`, `DELETE` — dispatched but no body parsing                            |
|           | content_gen    | `/v1beta/{model}:generateContent`                  | (modified) | `org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiContentGenHandler`                       | `POST` (generateContent). Pre-existing non-streaming parsing                                |
|           |                | `/v1beta/{model}:streamGenerateContent`            | ✅   |                                                                                                       | `POST` (streamGenerateContent) — **SSE streaming added in this round** (was `Unit`)         |
|           | embeddings     | `/v1beta/{model}:embedContent`                     | ✅   | `org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiEmbeddingsHandler`                             | `POST` (embedContent). Vertex AI `:predict` alias for `embed-*` models also routes here    |
|           |                | `/v1beta/{model}:batchEmbedContents`               |     | (mis-routed)                                                                                          | **Gap**: `isEmbeddingsUrl` only matches `:embedContent` / `:predict`; falls through to `GeminiContentGenHandler` and is mis-parsed |
|           |                | `/v1beta/{model}:asyncBatchEmbedContent`           |     | (mis-routed)                                                                                          | **Gap**: same as above                                                                       |
|           | models         | `/v1beta/models`                                   | ✅   | `org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiModelsHandler`                                 | `GET` (list). **No-op handler** — all overrides are `Unit`; only adapter-level cross-cutting attrs set |
|           |                | `/v1beta/models/{name}`                            |     |                                                                                                       | `GET` (get) — same no-op. `models.predict` / `models.predictLongRunning` route elsewhere (Imagen / Embeddings / ContentGen) |


# Attribute Mapping

## Average coverage

| Scope     | Average coverage % |
|-----------|-------------------:|
| OpenAI    |             41.08% |
| Anthropic |             69.53% |
| Gemini    |             36.25% |
| **Total** |         **47.39%** |

## Coverage summary

| Provider  | Handler                                  | Covered attributes | Coverage % |
|-----------|------------------------------------------|-------------------:|-----------:|
| OpenAI    | `AudioOpenAIApiEndpointHandler`          |               4/20 |        20% |
| OpenAI    | `BatchesOpenAIApiEndpointHandler`        |               5/33 |      15.1% |
| OpenAI    | `ConversationsOpenAIApiEndpointHandler`  |                3/7 |      42.8% |
| OpenAI    | `FilesOpenAIApiEndpointHandler`          |              16/21 |      76.1% |
| OpenAI    | `ModelsOpenAIApiEndpointHandler`         |                4/6 |        67% |
| OpenAI    | `ModerationsOpenAIApiEndpointHandler`    |                4/9 |      44.4% |
| Anthropic | `AnthropicCountTokensHandler`            |                2/9 |      22.2% |
| Anthropic | `AnthropicListEndpointHandler` — Batches |              15/24 |      62.5% |
| Anthropic | `AnthropicListEndpointHandler` — Models  |               9/13 |      69.2% |
| Anthropic | `AnthropicListEndpointHandler` — Files   |              10/13 |      76.9% |
| Gemini    | `GeminiCachedContentsHandler`            |               2/16 |      12.5% |
| Gemini    | `GeminiEmbeddingsHandler`                |                3/5 |        60% |

> Scope of the summary: **newly added** handlers only. Excluded from the table are: handlers that were modified rather than newly added (§7 `ResponsesOpenAIApiEndpointHandler`, §10 `AnthropicMessagesHandler` — a refactor of a hand-written implementation, §12 `GeminiContentGenHandler`) and the no-op §14 `GeminiModelsHandler` (0% coverage by construction).


## OpenAI

### 1. `AudioOpenAIApiEndpointHandler`

Info:
1. Request type: form-data
1. Response type: JSON
1. Covers endpoints (resource: [audio](https://developers.openai.com/api/reference/resources/audio)):
   1. [`POST /audio/transcriptions`](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create)
   2. [`POST /audio/translations`](https://developers.openai.com/api/reference/resources/audio/subresources/translations/methods/create)
   3. [`POST /audio/speech`](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create)
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
1. Covers endpoints (resource: [batches](https://developers.openai.com/api/reference/resources/batches)):
   1. [`POST /batches`](https://developers.openai.com/api/reference/resources/batches/methods/create)
   2. [`GET /batches/{batch_id}`](https://developers.openai.com/api/reference/resources/batches/methods/retrieve)
   3. [`GET /batches`](https://developers.openai.com/api/reference/resources/batches/methods/list)
   4. [`POST /batches/{batch_id}/cancel`](https://developers.openai.com/api/reference/resources/batches/methods/cancel)
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
1. Covers endpoints (resource: [conversations](https://developers.openai.com/api/reference/resources/conversations)):
   1. [`POST /conversations`](https://developers.openai.com/api/reference/resources/conversations/methods/create)
   2. [`GET /conversations/{conversation_id}`](https://developers.openai.com/api/reference/resources/conversations/methods/retrieve)
   3. [`POST /conversations/{conversation_id}`](https://developers.openai.com/api/reference/resources/conversations/methods/update) — **not implemented** (UPDATE; the dispatcher has no route for it)
   4. [`DELETE /conversations/{conversation_id}`](https://developers.openai.com/api/reference/resources/conversations/methods/delete)

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
1. Covers endpoints (resource: [files](https://developers.openai.com/api/reference/resources/files)):
   1. [`GET /files`](https://developers.openai.com/api/reference/resources/files/methods/list)
   2. [`POST /files`](https://developers.openai.com/api/reference/resources/files/methods/create)
   3. [`GET /files/{file_id}`](https://developers.openai.com/api/reference/resources/files/methods/retrieve)
   4. [`DELETE /files/{file_id}`](https://developers.openai.com/api/reference/resources/files/methods/delete)
   5. [`GET /files/{file_id}/content`](https://developers.openai.com/api/reference/resources/files/methods/content)
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
1. Covers endpoints (resource: [models](https://developers.openai.com/api/reference/resources/models)):
   1. [`GET /models`](https://developers.openai.com/api/reference/resources/models/methods/list)
   2. [`GET /models/{model}`](https://developers.openai.com/api/reference/resources/models/methods/retrieve)
   3. [`DELETE /models/{model}`](https://developers.openai.com/api/reference/resources/models/methods/delete) — **not implemented** (handler's `deriveOperationName` only knows `models.list` / `models.retrieve`; DELETE falls through to `models.list` by default)
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
1. Covers endpoints (resource: [moderations](https://developers.openai.com/api/reference/resources/moderations)):
   1. [`POST /moderations`](https://developers.openai.com/api/reference/resources/moderations/methods/create)
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
1. Covers endpoints (resource: [responses](https://developers.openai.com/api/reference/resources/responses); URL-derived `gen_ai.operation.name`):
   1. [`POST /responses`](https://developers.openai.com/api/reference/resources/responses/methods/create), [`GET /responses/{response_id}`](https://developers.openai.com/api/reference/resources/responses/methods/retrieve) → `generate_content`
   2. [`POST /responses/{response_id}/cancel`](https://developers.openai.com/api/reference/resources/responses/methods/cancel) → `response.cancel`

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
   1. [`POST /v1/messages/count_tokens`](https://platform.claude.com/docs/en/api/messages/count_tokens)
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
   - **Batches (6)** — resource: [messages.batches](https://platform.claude.com/docs/en/api/messages/batches):
     [`GET /v1/messages/batches`](https://platform.claude.com/docs/en/api/messages/batches/list),
     [`POST /v1/messages/batches`](https://platform.claude.com/docs/en/api/messages/batches/create),
     [`GET /v1/messages/batches/{id}`](https://platform.claude.com/docs/en/api/messages/batches/retrieve),
     [`POST /v1/messages/batches/{id}/cancel`](https://platform.claude.com/docs/en/api/messages/batches/cancel),
     [`DELETE /v1/messages/batches/{id}`](https://platform.claude.com/docs/en/api/messages/batches/delete),
     [`GET /v1/messages/batches/{id}/results`](https://platform.claude.com/docs/en/api/messages/batches/results)
   - **Models (2)** — resource: [models](https://platform.claude.com/docs/en/api/models):
     [`GET /v1/models`](https://platform.claude.com/docs/en/api/models/list),
     [`GET /v1/models/{model_id}`](https://platform.claude.com/docs/en/api/models/retrieve)
   - **Files (5)** — resource: [beta/files](https://platform.claude.com/docs/en/api/beta/files):
     [`POST /v1/files`](https://platform.claude.com/docs/en/api/beta/files/upload),
     [`GET /v1/files`](https://platform.claude.com/docs/en/api/beta/files/list),
     [`GET /v1/files/{id}`](https://platform.claude.com/docs/en/api/beta/files/retrieve_metadata),
     [`DELETE /v1/files/{id}`](https://platform.claude.com/docs/en/api/beta/files/delete),
     [`GET /v1/files/{id}/content`](https://platform.claude.com/docs/en/api/beta/files/download)
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
1. Covers endpoints (resource: [messages](https://platform.claude.com/docs/en/api/messages)):
   1. [`POST /v1/messages`](https://platform.claude.com/docs/en/api/messages/create)
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

> **Spec-type rule** is the same as the Anthropic section: only attribute names listed in the OTel GenAI registry count as **GenAI**; `gen_ai.`-prefixed attributes that are not in the registry (`gen_ai.prompt.{i}.*`, `gen_ai.completion.{i}.*`, `gen_ai.tool.{i}.function.{j}.*`, `gen_ai.response.list.*`, `gen_ai.response.embedding.*`, `gen_ai.usage.total_tokens`, `gen_ai.usage.{prompt,candidates}_tokens_details.{i}.*`, `gen_ai.request.{task_type,output_dimensionality}`) are **non-conventional GenAI**. The `gemini.*` namespace is **Custom**.

### 11. `GeminiCachedContentsHandler`

Info:
1. Request type: not parsed (handler ignores request bodies)
1. Response type: JSON
1. Covers endpoints (resource: [caching](https://ai.google.dev/api/caching); per the dispatcher; only LIST is meaningfully handled):
   1. [`POST /v1beta/cachedContents`](https://ai.google.dev/api/caching#method:-cachedcontents.create) (`cachedContents.create`) — body **not** parsed
   2. [`GET /v1beta/cachedContents`](https://ai.google.dev/api/caching#method:-cachedcontents.list) (`cachedContents.list`) — handled
   3. [`GET /v1beta/{name=cachedContents/*}`](https://ai.google.dev/api/caching#method:-cachedcontents.get) (`cachedContents.get`) — body **not** parsed
   4. [`PATCH /v1beta/{cachedContent.name=cachedContents/*}`](https://ai.google.dev/api/caching#method:-cachedcontents.patch) (`cachedContents.patch`) — body **not** parsed
   5. [`DELETE /v1beta/{name=cachedContents/*}`](https://ai.google.dev/api/caching#method:-cachedcontents.delete) (`cachedContents.delete`) — empty response, N/A
1. **Attributes coverage: 2/16 = 12.5%** (denominator: union of unique fields documented across the 5 methods, including `CachedContent` resource fields).

> **Design note.** The handler is dispatched for every cachedContents URL by the parent adapter's `isCachedContentsUrl()` (any URL containing the `cachedContents` segment) but only `handleResponseAttributes` parses anything, and only the LIST shape (`{cachedContents:[…], nextPageToken}`). For `create`, `get`, `patch` the same handler runs against a single-resource `CachedContent` body and silently extracts nothing — the spans look identical except for `gen_ai.operation.name`.

| Original attribute  | Source                  | Mapped to attribute(s)          | Specification type     | Note                                                                       |
|---------------------|-------------------------|---------------------------------|------------------------|----------------------------------------------------------------------------|
| `pageSize`          | req:list (query)        | ❌                               |                        |                                                                            |
| `pageToken`         | req:list (query)        | ❌                               |                        |                                                                            |
| `contents`          | req:create              | ❌                               |                        |                                                                            |
| `tools`             | req:create              | ❌                               |                        |                                                                            |
| `expiration`        | req:create, req:patch   | ❌                               |                        |                                                                            |
| `displayName`       | req:create              | ❌                               |                        |                                                                            |
| `model`             | req:create              | ❌                               |                        |                                                                            |
| `systemInstruction` | req:create              | ❌                               |                        |                                                                            |
| `toolConfig`        | req:create              | ❌                               |                        |                                                                            |
| `cachedContents`    | resp:list (array)       | `gen_ai.response.list.count`    | non-conventional GenAI | Length only                                                                |
| `nextPageToken`     | resp:list               | `gen_ai.response.list.has_more` | non-conventional GenAI | `true` iff `nextPageToken` is non-empty (boolean inferred, not the token)  |
| `name`              | resp:`CachedContent`    | ❌                               |                        | Resource id; would fit `gen_ai.cached_content.id` or similar               |
| `createTime`        | resp:`CachedContent`    | ❌                               |                        |                                                                            |
| `updateTime`        | resp:`CachedContent`    | ❌                               |                        |                                                                            |
| `expireTime`        | resp:`CachedContent`    | ❌                               |                        |                                                                            |
| `usageMetadata`     | resp:`CachedContent`    | ❌                               |                        | OTel `gen_ai.usage.*` partial fits would be possible                       |

### 12. `GeminiContentGenHandler` — modifications since `6e028bd2`

Info:
1. Request type: JSON
1. Response type: JSON (non-streaming) or `text/event-stream` (streaming)
1. Covers endpoints (resource: [generate-content](https://ai.google.dev/api/generate-content)):
   1. [`POST /v1beta/{model}:generateContent`](https://ai.google.dev/api/generate-content#method:-models.generatecontent) — pre-existing non-streaming parsing
   2. [`POST /v1beta/{model}:streamGenerateContent`](https://ai.google.dev/api/generate-content#method:-models.streamgeneratecontent) — **SSE streaming added in this patch** (`handleStreaming` was `Unit` before)
1. Pre-existing non-streaming coverage: **request 12/19 ≈ 63%**, **response 9/14 ≈ 64%**. The streaming branch adds the rows below; the non-streaming tables are unchanged and not repeated here.

#### 12.1 New SSE streaming branch (delta vs. `6e028bd2`)

The patch replaces `override fun handleStreaming(span: Span, events: String) = Unit` with a full implementation. It splits the SSE body on `\n\n`, parses each `data:` line as a JSON chunk, accumulates `candidates[0].content.parts[].text` across chunks, and on the **last** chunk extracts metadata.

| New attribute (streaming only)     | Source                                          | Mapped to attribute(s)              | Specification type     | Note                                                       |
|------------------------------------|-------------------------------------------------|-------------------------------------|------------------------|------------------------------------------------------------|
| accumulated text                   | SSE chunks: `candidates[0].content.parts[].text`| `gen_ai.completion.0.content`       | non-conventional GenAI | Concatenated from all chunks                               |
| `responseId` (last chunk)          | SSE last chunk                                  | `gen_ai.response.id`                | GenAI                  |                                                            |
| `modelVersion` (last chunk)        | SSE last chunk                                  | `gen_ai.response.model`             | GenAI                  |                                                            |
| `usageMetadata.promptTokenCount`   | SSE last chunk                                  | `gen_ai.usage.input_tokens`         | GenAI                  |                                                            |
| `usageMetadata.candidatesTokenCount` | SSE last chunk                                | `gen_ai.usage.output_tokens`        | GenAI                  |                                                            |
| `candidates[0].finishReason`       | SSE last chunk                                  | `gen_ai.completion.0.finish_reason` | non-conventional GenAI | Registry has `gen_ai.response.finish_reasons` (array)      |
| (parse exception)                  | runCatching                                     | span ERROR status + `recordException`| —                      | Streaming-only error handling                              |

Streaming gaps (relative to the same-shape non-streaming response): `usageMetadata.totalTokenCount`, `usageMetadata.cachedContentTokenCount`, `usageMetadata.thoughtsTokenCount`, `usageMetadata.toolUsePromptTokenCount`, `promptTokensDetails`, `candidatesTokensDetails`, `cacheTokensDetails`, `promptFeedback`, per-candidate roles / tool calls (the streaming branch only handles candidate 0's text, no tool-call accumulation).

#### 12.2 Non-streaming attributes (unchanged) — quick reference for completeness

Request:
- `contents[i].{role,parts}` → `gen_ai.prompt.{i}.{role,content}` (non-conventional GenAI; registry has `gen_ai.input.messages`)
- `tools[i].functionDeclarations[j].{name,description,parameters,parametersJsonSchema,type}` → `gen_ai.tool.{i}.function.{j}.{name,description,parameters,type}` (non-conventional GenAI; registry has flat `gen_ai.tool.definitions`)
- `generationConfig.{candidateCount,maxOutputTokens,temperature,topP,topK}` → `gen_ai.request.{choice.count,max_tokens,temperature,top_p,top_k}` (**all GenAI**)
- Inline media `inlineData.{data,mimeType}` → `tracy.input.media.*` via `MediaContentExtractor` (Custom)
- ❌: `systemInstruction`, `toolConfig`, `safetySettings`, `cachedContent`, `serviceTier`, `store`, `generationConfig.{stopSequences,responseMimeType,responseSchema}`

Response (non-streaming):
- `responseId` → `gen_ai.response.id` (GenAI)
- `modelVersion` → `gen_ai.response.model` (GenAI)
- `candidates[i].{content.role,content.parts text/functionCall,finishReason}` → `gen_ai.completion.{i}.{role,content,finish_reason,tool.{j}.{name,arguments}}` (non-conventional GenAI)
- `usageMetadata.{promptTokenCount,candidatesTokenCount,totalTokenCount}` → `gen_ai.usage.{input_tokens,output_tokens,total_tokens}` (first two GenAI; `total_tokens` non-conventional — registry has no `gen_ai.usage.total_tokens`)
- `usageMetadata.{promptTokensDetails,candidatesTokensDetails}[i].{modality,tokenCount}` → snake-cased `gen_ai.usage.{prompt,candidates}_tokens_details.{i}.{modality,token_count}` (non-conventional GenAI)
- ❌: `promptFeedback`, `modelStatus`, `usageMetadata.{cachedContentTokenCount,toolUsePromptTokenCount,thoughtsTokenCount,cacheTokensDetails}`

### 13. `GeminiEmbeddingsHandler`

Info:
1. Request type: JSON
1. Response type: JSON
1. Covers endpoints (resource: [embeddings](https://ai.google.dev/api/embeddings)):
   1. [`POST /v1beta/{model}:embedContent`](https://ai.google.dev/api/embeddings#method:-models.embedcontent) — handled
   2. Vertex AI alias `:predict` for embed-named models — handled (operation name normalised to `embedContent`)
   3. [`POST /v1beta/{model}:batchEmbedContents`](https://ai.google.dev/api/embeddings#method:-models.batchembedcontents) — **not handled** (mis-routed to `GeminiContentGenHandler`)
   4. [`POST /v1beta/{model}:asyncBatchEmbedContent`](https://ai.google.dev/api/embeddings#method:-models.asyncbatchembedcontent) — **not handled** (mis-routed)
1. **Attributes coverage: 3/5 = 60%** for `embedContent` body fields (denominator: `content`, `taskType`, `title`, `outputDimensionality`, `embedding.values`).

> **Routing gap.** The dispatcher's `isEmbeddingsUrl` check matches only `operation == "embedContent"` or `operation == "predict"` (with embed-named models). Vertex AI / Generative Language API methods `:batchEmbedContents` and `:asyncBatchEmbedContent` therefore **fall through to `GeminiContentGenHandler`**, which then tries to parse them as content-generation responses (`candidates[]`, `usageMetadata`) and silently sets nothing useful.

| Original attribute      | Source           | Mapped to attribute(s)                   | Specification type      | Note                                                                |
|-------------------------|------------------|------------------------------------------|-------------------------|---------------------------------------------------------------------|
| `content`               | req              | ❌                                        |                         | The actual text being embedded — not traced                         |
| `taskType`              | req              | `gen_ai.request.task_type`               | non-conventional GenAI  |                                                                     |
| `title`                 | req              | ❌                                        |                         |                                                                     |
| `outputDimensionality`  | req              | `gen_ai.request.output_dimensionality`   | non-conventional GenAI  |                                                                     |
| `embedding.values`      | resp             | `gen_ai.response.embedding.dimension`    | non-conventional GenAI  | Length only — actual vector not traced. Registry has `gen_ai.embeddings.dimension.count` (a near match but different name) |
| `usageMetadata`         | resp             | ❌                                        |                         |                                                                     |

The handler also unconditionally sets:
- `gen_ai.operation.name = "embedContent"` *(GenAI; overrides URL-derived `predict` for the Vertex AI alias)*
- `gen_ai.output.type = "embedding"` *(GenAI)*
- `gemini.api.type = "models"` *(Custom; redundant with the parent adapter's setter)*

### 14. `GeminiModelsHandler`

Info:
1. Request type: not parsed (handler is a pass-through)
1. Response type: not parsed
1. Covers endpoints (resource: [models](https://ai.google.dev/api/models)):
   1. [`GET /v1beta/models`](https://ai.google.dev/api/models#method:-models.list) (`models.list`)
   2. [`GET /v1beta/models/{name}`](https://ai.google.dev/api/models#method:-models.get) (`models.get`)

   *Not routed here* (covered by other handlers despite the user listing them under Models): [`models.predict`](https://ai.google.dev/api/models#method:-models.predict), [`models.predictLongRunning`](https://ai.google.dev/api/models#method:-models.predictlongrunning).
1. **Attributes coverage: 0/N = 0%** — the handler does not parse any documented body field.

> **No-op handler.** All three overrides are literally `Unit`. The handler exists only to win dispatch over `GeminiContentGenHandler` for URLs that contain the `models` segment but no `:operation` suffix. Spans for these routes carry only the cross-cutting attributes that the parent `GeminiLLMTracingAdapter` sets (`gemini.api.type="models"`, plus path-derived `gen_ai.request.model` when the URL ends with a model id). None of the documented `Model` resource fields (`name`, `baseModelId`, `version`, `displayName`, `description`, `inputTokenLimit`, `outputTokenLimit`, `supportedGenerationMethods`, `temperature`, `maxTemperature`, `topP`, `topK`) reach the span. The `nextPageToken` and `models[]` array length on `models.list` are likewise not parsed.

> The user-listed methods `models.predict` and `models.predictLongRunning` are **not** routed to this handler. They are URLs of shape `…/models/{model}:predict` (or `:predictLongRunning`) — the trailing `:operation` excludes them from `isModelsUrl()`. Depending on the model name they go to `GeminiImagenHandler` (imagen-*), `GeminiEmbeddingsHandler` (embed-*), or fall through to `GeminiContentGenHandler`.

---

**Cross-cutting attributes** — every Gemini handler emits the following on **every** span (set by `GeminiLLMTracingAdapter.getRequestBodyAttributes`); they do not correspond to documented Gemini request/response **fields** and are not counted toward per-handler coverage percentages:

- `gen_ai.provider.name = "gemini"` *(GenAI)*
- `server.address`, `server.port` *(OTel HTTP semconv)*
- `gen_ai.operation.name` *(GenAI; URL-derived from the `:operation` suffix, or `embedContent` after `GeminiEmbeddingsHandler`'s normalisation)*
- `gen_ai.request.model` *(GenAI; URL-derived — the part of the last path segment before `:`)*
- `gemini.api.type` *(Custom; one of `models` / `cachedContents`)*
- `http.response.status_code` *(OTel HTTP semconv)*

