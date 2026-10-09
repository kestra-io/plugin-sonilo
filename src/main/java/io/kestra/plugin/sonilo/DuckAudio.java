package io.kestra.plugin.sonilo;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Duck music under voice",
    description = "Submits POST /v1/audio-ducking, polls GET /v1/tasks/{task_id}, and stores the mix in Kestra internal storage. "
        + "Provide exactly one voice source and exactly one music source. A voice video is returned as a video mix. "
        + "Killing the task stops the local wait only. Sonilo has no cancel endpoint, so remote work can continue and can still be billed."
)
@Plugin(
    examples = {
        @Example(
            title = "Duck background music under a voice track",
            full = true,
            code = """
                id: duck_voice_and_music
                namespace: company.media

                inputs:
                  - id: voice_uri
                    type: FILE
                  - id: music_uri
                    type: FILE

                tasks:
                  - id: duck_audio
                    type: io.kestra.plugin.sonilo.DuckAudio
                    apiToken: "{{ secret('SONILO_API_TOKEN') }}"
                    voice: "{{ inputs.voice_uri }}"
                    music: "{{ inputs.music_uri }}"
                """
        )
    }
)
public class DuckAudio extends AbstractSonilo<DuckAudio.Output> implements RunnableTask<DuckAudio.Output> {
    @Schema(
        title = "Voice from internal storage",
        description = "Kestra URI of the foreground voice. Audio or video is accepted. Set this or voiceUrl, not both."
    )
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> voice;

    @Schema(
        title = "Voice URL",
        description = "URL of the foreground voice. Set this or voice, not both."
    )
    @PluginProperty(group = "source")
    private Property<String> voiceUrl;

    @Schema(
        title = "Music from internal storage",
        description = "Kestra URI of the background music. The music input must be audio. Set this or musicUrl, not both."
    )
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> music;

    @Schema(
        title = "Music URL",
        description = "URL of the background music. Set this or music, not both."
    )
    @PluginProperty(group = "source")
    private Property<String> musicUrl;

    @Override
    protected Output execute(RunContext runContext, SoniloClient client) throws Exception {
        String rVoice = optionalText(runContext, voice).orElse(null);
        String rVoiceUrl = optionalText(runContext, voiceUrl).orElse(null);
        String rMusic = optionalText(runContext, music).orElse(null);
        String rMusicUrl = optionalText(runContext, musicUrl).orElse(null);
        requireExactlyOne("voice", rVoice, "voiceUrl", rVoiceUrl);
        requireExactlyOne("music", rMusic, "musicUrl", rMusicUrl);

        Map<String, Object> form = new LinkedHashMap<>();
        if (rVoice != null) {
            form.put("voice_file", client.storageFile(rVoice, "voice.wav").file());
        } else {
            form.put("voice_url", rVoiceUrl);
        }
        if (rMusic != null) {
            form.put("music_file", client.storageFile(rMusic, "music.wav").file());
        } else {
            form.put("music_url", rMusicUrl);
        }
        return Output.from(client.generate("/audio-ducking", form, renderPollInterval(runContext), renderWaitTimeout(runContext)));
    }

    @Builder
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Sonilo task identifier")
        private final String taskId;

        @Schema(title = "Sonilo task status")
        private final String status;

        @Schema(title = "Internal storage URI of the ducked mix")
        private final URI audioUri;

        @Schema(title = "MIME type of the mix")
        private final String contentType;

        @Schema(title = "Ducking output container, audio or video")
        private final String outputType;

        private static Output from(SoniloClient.Generation generation) {
            return Output.builder()
                .taskId(generation.taskId())
                .status(generation.status())
                .audioUri(generation.audioUri())
                .contentType(generation.contentType())
                .outputType(generation.outputType())
                .build();
        }
    }
}
