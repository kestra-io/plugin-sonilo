# Kestra Sonilo Plugin

## What

- Provides plugin components under `io.kestra.plugin.sonilo`.
- Tasks: `GenerateMusicFromText`, `GenerateMusicFromVideo`, `GenerateSfxFromText`, `GenerateSfxFromVideo`, and `DuckAudio`.
- `Trigger` polls `GET /v1/tasks/{taskId}` and fires once when that task reaches a terminal status.
- Shared connection and HTTP behavior live on `AbstractSonilo`, `SoniloConnection`, `SoniloClient`, `SoniloResponse`, and `SoniloSupport`.

## Why

- Media teams need to generate music, sound effects, and ducked mixes from a Kestra flow and keep the audio after Sonilo's presigned URLs expire.
- A flow can wait for an existing Sonilo task without a webhook. Sonilo does not offer one.
- The plugin gives those tasks a repeatable build, test, and publish path.

## How

### Architecture

Single-module plugin. Source package:

- `io.kestra.plugin.sonilo`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.sonilo.GenerateMusicFromText`
- `io.kestra.plugin.sonilo.GenerateMusicFromVideo`
- `io.kestra.plugin.sonilo.GenerateSfxFromText`
- `io.kestra.plugin.sonilo.GenerateSfxFromVideo`
- `io.kestra.plugin.sonilo.DuckAudio`
- `io.kestra.plugin.sonilo.Trigger`

### Project Structure

```
plugin-sonilo/
├── src/main/java/io/kestra/plugin/sonilo/
├── src/test/java/io/kestra/plugin/sonilo/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Call Sonilo only through `io.kestra.core.http.client.HttpClient`.
- Do not send mix knobs that are absent from the Sonilo OpenAPI and JavaScript SDK (`ducking_ratio`, `attack_ms`, `release_ms`, `isolate_vocals`).

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
- https://platform.sonilo.com/openapi.json
