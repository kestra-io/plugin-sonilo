# Sonilo

Generate music, sound effects, and ducked mixes with the Sonilo API, then store the audio in Kestra internal storage.

Sonilo Platform API keys are bearer tokens from [platform.sonilo.com](https://platform.sonilo.com). They are separate from a sonilo.com app login. The default API host is `https://api.sonilo.com`. Set `apiToken` from a secret, for example `{{ secret('SONILO_API_TOKEN') }}`.

## Tasks

- `GenerateMusicFromText` calls `POST /v1/text-to-music`.
- `GenerateMusicFromVideo` calls `POST /v1/video-to-music`. Provide exactly one of `video` or `videoUrl`.
- `GenerateSfxFromText` calls `POST /v1/text-to-sfx`.
- `GenerateSfxFromVideo` calls `POST /v1/video-to-sfx`. Provide exactly one of `video` or `videoUrl`.
- `DuckAudio` calls `POST /v1/audio-ducking`. Provide exactly one voice source and exactly one music source.

Music generation streams NDJSON audio by default. wav, mp3, more than one variant, stems, ducking, and `preserveSpeech` are sent as `mode=async`. The task then polls `GET /v1/tasks/{task_id}` until the task finishes. Sound effects and audio ducking are always asynchronous. Each task stores the finished audio and exposes it as `audioUri`. Presigned media URLs are downloaded without the bearer token, because the CDN rejects extra authorization.

Polling defaults to every 3 seconds and gives up after 30 minutes. Stem separation often finishes in a few minutes.

## Trigger

`Trigger` polls one Sonilo task. Sonilo does not offer a webhook. Each interval sends one `GET /v1/tasks/{taskId}`. The default interval is `PT30S`.

When the task reaches a terminal status, the trigger starts one execution for that task id and status. Success and failure both fire. A successful file is copied into internal storage because presigned URLs expire. A failed task exposes `error` and `errorCode` and does not download a file. Pass the task id as a literal value. `{{ trigger.taskId }}` is the id from a fired execution, so it cannot identify the task on the first poll.

## Cancellation

Killing a running task stops the local HTTP call and the local poll. Sonilo has no cancel-job API, so work already accepted by Sonilo can continue and can still be billed.

Generated audio is covered by [Sonilo's Terms of Service](https://sonilo.com/terms-of-service).
