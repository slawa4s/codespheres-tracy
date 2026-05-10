# Changelog

## Unreleased

- Renamed non-registry `gen_ai.*` pagination and result attributes in `ListVideosHandler` and `DeleteVideoHandler` to the `tracy.*` namespace: `gen_ai.request.after/limit/order` → `tracy.request.after/limit/order`, `gen_ai.response.first_id/last_id/has_more/videos_count` → `tracy.response.first_id/last_id/has_more/videos_count`, `gen_ai.request.video.requested_id` → `tracy.request.video.requested_id`, `gen_ai.response.deleted` → `tracy.response.deleted`. These attributes are not part of the OTel GenAI registry; using `gen_ai.*` for them risked silent collisions with future registry standardisation.
