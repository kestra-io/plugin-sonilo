package io.kestra.plugin.sonilo;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
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
    title = "Generate sound effects from a video",
    description = "Submits a video URI or video URL to POST /v1/video-to-sfx, polls GET /v1/tasks/{task_id}, and stores the audio in Kestra internal storage. "
        + "Provide exactly one video source. The task returns audio only. Killing the task stops the local wait only. Sonilo has no cancel endpoint, so remote work can continue and can still be billed."
)
@Plugin(
    examples = {
        @Example(
            title = "Build sound effects for a video URL",
            full = true,
            code = """
                id: video_sfx
                namespace: company.media

                inputs:
                  - id: video_url
                    type: STRING

                tasks:
                  - id: generate_sfx
                    type: io.kestra.plugin.sonilo.GenerateSfxFromVideo
                    apiToken: "{{ secret('SONILO_API_TOKEN') }}"
                    videoUrl: "{{ inputs.video_url }}"
                    prompt: "Soft room tone with distant traffic"
                """
        )
    }
)
public class GenerateSfxFromVideo extends AbstractSonilo<GenerateSfxFromVideo.Output> implements RunnableTask<GenerateSfxFromVideo.Output> {
    @Schema(
        title = "Video from internal storage",
        description = "Kestra URI of the video. Set this or videoUrl, not both."
    )
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> video;

    @Schema(
        title = "Video URL",
        description = "Public or presigned URL of the video. Set this or video, not both."
    )
    @PluginProperty(group = "source")
    private Property<String> videoUrl;

    @Schema(
        title = "Sound effect prompt",
        description = "Optional guidance for the effect. Sonilo documents a practical limit of 2000 characters."
    )
    @PluginProperty(group = "processing")
    private Property<String> prompt;

    @Schema(
        title = "Segment plan",
        description = "Optional JSON string of timed effect prompts. Sent as the segments form field."
    )
    @PluginProperty(group = "processing")
    private Property<String> segments;

    @Schema(
        title = "Audio format",
        description = "Optional container such as mp3, wav, or m4a. Sent as audio_format."
    )
    @PluginProperty(group = "processing")
    private Property<String> audioFormat;

    @Override
    protected Output execute(RunContext runContext, SoniloClient client) throws Exception {
        String rVideo = optionalText(runContext, video).orElse(null);
        String rVideoUrl = optionalText(runContext, videoUrl).orElse(null);
        requireExactlyOne("video", rVideo, "videoUrl", rVideoUrl);
        Map<String, Object> form = new LinkedHashMap<>();
        if (rVideo != null) {
            form.put("video", client.storageFile(rVideo, "video.mp4").file());
        } else {
            form.put("video_url", rVideoUrl);
        }
        optionalText(runContext, prompt).ifPresent(value -> form.put("prompt", value));
        optionalText(runContext, segments).ifPresent(value -> form.put("segments", value));
        optionalText(runContext, audioFormat).ifPresent(value -> form.put("audio_format", value));
        return Output.from(client.generate("/video-to-sfx", form, renderPollInterval(runContext), renderWaitTimeout(runContext)));
    }

    @Builder
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Sonilo task identifier")
        private final String taskId;

        @Schema(title = "Sonilo task status")
        private final String status;

        @Schema(title = "Internal storage URI of the primary audio")
        private final URI audioUri;

        @Schema(title = "Internal storage URIs of the audio tracks")
        private final List<URI> audioUris;

        @Schema(title = "Generated title")
        private final String title;

        @Schema(title = "MIME type of the primary file")
        private final String contentType;

        private static Output from(SoniloClient.Generation generation) {
            return Output.builder()
                .taskId(generation.taskId())
                .status(generation.status())
                .audioUri(generation.audioUri())
                .audioUris(generation.audioUris())
                .title(generation.title())
                .contentType(generation.contentType())
                .build();
        }
    }
}
