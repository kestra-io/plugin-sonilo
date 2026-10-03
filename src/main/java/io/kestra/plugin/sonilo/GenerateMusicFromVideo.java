package io.kestra.plugin.sonilo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.plugin.sonilo.AbstractSonilo.Mode;
import io.kestra.plugin.sonilo.AbstractSonilo.Output;
import io.kestra.plugin.sonilo.AbstractSonilo.OutputFormat;
import io.kestra.core.runners.RunContext;
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
    title = "Generate music from a video",
    description = "Sends a video URI or video URL to POST /v1/video-to-music and stores the audio in Kestra internal storage. "
        + "Provide exactly one video source. Stream mode is the default. wav, mp3, multiple variants, stems, ducking, and preserveSpeech switch the request to async. "
        + "Killing the task stops the local wait only. Sonilo has no cancel endpoint, so remote work can continue and can still be billed."
)
@Plugin(
    examples = {
        @Example(
            title = "Score a video that is already in internal storage",
            full = true,
            code = """
                id: score_completed_video
                namespace: company.media

                inputs:
                  - id: video_uri
                    type: STRING

                tasks:
                  - id: generate_music
                    type: io.kestra.plugin.sonilo.GenerateMusicFromVideo
                    apiToken: "{{ secret('SONILO_API_TOKEN') }}"
                    video: "{{ inputs.video_uri }}"
                """
        )
    }
)
public class GenerateMusicFromVideo extends AbstractSonilo {
    @Schema(
        title = "Video from internal storage",
        description = "Kestra URI of the video to score. Set this or videoUrl, not both."
    )
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> video;

    @Schema(
        title = "Video URL",
        description = "Public or presigned URL of the video to score. Set this or video, not both."
    )
    @PluginProperty(group = "source")
    private Property<String> videoUrl;

    @Schema(
        title = "Music prompt",
        description = "Optional guidance for the score. Sonilo documents a practical limit of 2000 characters."
    )
    private Property<String> prompt;

    @Schema(
        title = "Segment plan",
        description = "Optional JSON string of timed segment prompts. Sent as the segments form field."
    )
    private Property<String> segments;

    @Schema(
        title = "Generation mode",
        description = "stream returns NDJSON audio. async is required for wav, mp3, multiple variants, stems, ducking, and preserveSpeech. "
            + "When omitted, the task selects async if one of those options is set."
    )
    private Property<Mode> mode;

    @Schema(
        title = "Output format",
        description = "m4a, wav, or mp3. wav and mp3 require async mode. Streaming audio is always m4a."
    )
    private Property<OutputFormat> outputFormat;

    @Schema(
        title = "Keep isolated speech beside the score",
        description = "When true, Sonilo also returns isolated source vocals and a speech-preserving mux. Requires async mode."
    )
    private Property<Boolean> preserveSpeech;

    @Schema(
        title = "Also return a ducked mix",
        description = "When true, Sonilo dips the generated music under the video speech and returns that mix beside the clean audio. Requires async mode."
    )
    private Property<Boolean> ducking;

    @Schema(
        title = "Prompt influence",
        description = "How strongly the music follows the prompt, from 0 to 1. Lower values let the video lead. Omitted values keep Sonilo's default. Zero is sent."
    )
    private Property<Double> promptInfluence;

    @Schema(
        title = "Number of variants",
        description = "Optional integer from 1 to 10. Values above 1 require async mode and are billed per variant."
    )
    private Property<Integer> variantsNum;

    @Schema(
        title = "Also return separated stems",
        description = "When true, Sonilo splits the generated music into drums, bass, vocals, and other. Requires async mode. Stem failure does not fail the generation."
    )
    private Property<Boolean> stems;

    @Override
    protected Output execute(RunContext runContext, SoniloClient client) throws Exception {
        String renderedVideo = optionalText(runContext, video).orElse(null);
        String renderedVideoUrl = optionalText(runContext, videoUrl).orElse(null);
        requireExactlyOne("video", renderedVideo, "videoUrl", renderedVideoUrl);
        OutputFormat renderedFormat = optionalEnum(runContext, outputFormat, OutputFormat.class).orElse(null);
        Integer renderedVariants = optionalInteger(runContext, variantsNum).orElse(null);
        if (renderedVariants != null) {
            requireRange("variantsNum", renderedVariants, 1, 10);
        }
        Optional<Double> renderedInfluence = optionalDouble(runContext, promptInfluence);
        renderedInfluence.ifPresent(value -> requireRange("promptInfluence", value, 0, 1));
        Optional<Boolean> renderedSpeech = optionalBoolean(runContext, preserveSpeech);
        Optional<Boolean> renderedDucking = optionalBoolean(runContext, ducking);
        Optional<Boolean> renderedStems = optionalBoolean(runContext, stems);
        boolean asyncOptions = SoniloSupport.needsAsync(
            renderedFormat == null ? null : renderedFormat.name(),
            renderedVariants,
            renderedStems.orElse(null),
            renderedDucking.orElse(null),
            renderedSpeech.orElse(null)
        );
        Optional<Mode> renderedMode = optionalEnum(runContext, mode, Mode.class);
        if (renderedMode.isEmpty() && asyncOptions) {
            runContext.logger().info("Sonilo music options require async mode; sending mode=async.");
        }
        String selected = SoniloSupport.selectMode(renderedMode.map(Enum::name).orElse(null), asyncOptions);

        Map<String, Object> form = new LinkedHashMap<>();
        if (renderedVideo != null) {
            form.put("video", client.storageFile(renderedVideo, "video.mp4").file());
        } else {
            form.put("video_url", renderedVideoUrl);
        }
        optionalText(runContext, prompt).ifPresent(value -> form.put("prompt", value));
        optionalText(runContext, segments).ifPresent(value -> form.put("segments", value));
        form.put("mode", selected);
        if (renderedFormat != null) {
            form.put("output_format", renderedFormat.name());
        }
        renderedSpeech.ifPresent(value -> form.put("preserve_speech", Boolean.toString(value)));
        renderedDucking.ifPresent(value -> form.put("ducking", Boolean.toString(value)));
        renderedInfluence.ifPresent(value -> form.put("prompt_influence", SoniloSupport.formatNumber(value)));
        if (renderedVariants != null) {
            form.put("variants_num", SoniloSupport.formatNumber(renderedVariants));
        }
        renderedStems.ifPresent(value -> form.put("stems", Boolean.toString(value)));
        return client.generate("/video-to-music", form, renderPollInterval(runContext), renderWaitTimeout(runContext));
    }
}
