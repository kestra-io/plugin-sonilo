package io.kestra.plugin.sonilo;

import java.util.LinkedHashMap;
import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.sonilo.AbstractSonilo.Output;
import io.swagger.v3.oas.annotations.media.Schema;
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
public class GenerateSfxFromVideo extends AbstractSonilo {
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
    private Property<String> prompt;

    @Schema(
        title = "Segment plan",
        description = "Optional JSON string of timed effect prompts. Sent as the segments form field."
    )
    private Property<String> segments;

    @Schema(
        title = "Audio format",
        description = "Optional container such as mp3, wav, or m4a. Sent as audio_format."
    )
    private Property<String> audioFormat;

    @Override
    protected Output execute(RunContext runContext, SoniloClient client) throws Exception {
        String renderedVideo = optionalText(runContext, video).orElse(null);
        String renderedVideoUrl = optionalText(runContext, videoUrl).orElse(null);
        requireExactlyOne("video", renderedVideo, "videoUrl", renderedVideoUrl);
        Map<String, Object> form = new LinkedHashMap<>();
        if (renderedVideo != null) {
            form.put("video", client.storageFile(renderedVideo, "video.mp4").file());
        } else {
            form.put("video_url", renderedVideoUrl);
        }
        optionalText(runContext, prompt).ifPresent(value -> form.put("prompt", value));
        optionalText(runContext, segments).ifPresent(value -> form.put("segments", value));
        optionalText(runContext, audioFormat).ifPresent(value -> form.put("audio_format", value));
        return client.generate("/video-to-sfx", form, renderPollInterval(runContext), renderWaitTimeout(runContext));
    }
}
