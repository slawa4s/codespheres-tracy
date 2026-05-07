
# Summary


| Provider | Endpoint Class | Endpoint Route          | New  | Implemented by                                              | Note                                                             |
|----------|----------------|-------------------------|------|-------------------------------------------------------------|------------------------------------------------------------------|
| OpenAI   | audio          | `/audio/transcriptions` | ✅    | `org.jetbrains.ai.tracy.ktor.AudioOpenAIApiEndpointHandler` |                                                                  |
|          |                | `/audio/translations`   |      |                                                             |                                                                  |
|          |                | `/audio/speech`         |      |                                                             |                                                                  |
|          |                | `/audio/voices`         |      |                                                             | Not generated because openai-java:4.5.0 doesn't expose the route |


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






4. batches: BatchesOpenAIApiEndpointHandler
3. conversations: ConversationsOpenAIApiEndpointHandler
1. files: FilesOpenAIApiEndpointHandler
1. models: ModelsOpenAIApiEndpointHandler
1. moderations: ModerationsOpenAIApiEndpointHandler