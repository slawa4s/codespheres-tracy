# Changelog

## Unreleased

- Moved OpenAI Videos list pagination attributes (`limit`, `order`, `after`) and list-response metadata (`first_id`, `last_id`, `has_more`, `videos_count`) from the non-registry `gen_ai.*` namespace to `tracy.*` to comply with the OTel GenAI semantic-convention attribute-naming policy.
