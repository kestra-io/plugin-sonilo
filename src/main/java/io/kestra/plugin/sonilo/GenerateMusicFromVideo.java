package io.kestra.plugin.sonilo;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.sonilo.AbstractSonilo.Mode;
import io.kestra.plugin.sonilo.AbstractSonilo.OutputFormat;
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
                    type: FILE

                tasks:
                  - id: generate_music
                    type: io.kestra.plugin.sonilo.GenerateMusicFromVideo
                    apiToken: "{{ secret('SONILO_API_TOKEN') }}"
                    video: "{{ inputs.video_uri }}"
                """
        )
    }
)
public class GenerateMusicFromVideo extends AbstractSonilo<GenerateMusicFromVideo.Output> implements RunnableTask<GenerateMusicFromVideo.Output> {
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
    @PluginProperty(group = "processing")
    private Property<String> prompt;

    @Schema(
        title = "Segment plan",
        description = "Optional JSON string of timed segment prompts. Sent as the segments form field."
    )
    @PluginProperty(group = "processing")
    private Property<String> segments;

    @Schema(
        title = "Generation mode",
        description = "stream returns NDJSON audio. async is required for wav, mp3, multiple variants, stems, ducking, and preserveSpeech. "
            + "When omitted, the task selects async if one of those options is set."
    )
    @PluginProperty(group = "processing")
    private Property<Mode> mode;

    @Schema(
        title = "Output format",
        description = "m4a, wav, or mp3. wav and mp3 require async mode. Streaming audio is always m4a."
    )
    @PluginProperty(group = "processing")
    private Property<OutputFormat> outputFormat;

    @Schema(
        title = "Keep isolated speech beside the score",
        description = "When true, Sonilo also returns isolated source vocals and a speech-preserving mux. Requires async mode."
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> preserveSpeech;

    @Schema(
        title = "Also return a ducked mix",
        description = "When true, Sonilo dips the generated music under the video speech and returns that mix beside the clean audio. Requires async mode."
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> ducking;

    @Schema(
        title = "Prompt influence",
        description = "How strongly the music follows the prompt, from 0 to 1. Lower values let the video lead. Omitted values keep Sonilo's default. Zero is sent."
    )
    @PluginProperty(group = "processing")
    private Property<Double> promptInfluence;

    @Schema(
        title = "Number of variants",
        description = "Optional integer from 1 to 10. Values above 1 require async mode and are billed per variant."
    )
    @PluginProperty(group = "processing")
    private Property<Integer> variantsNum;

    @Schema(
        title = "Also return separated stems",
        description = "When true, Sonilo splits the generated music into drums, bass, vocals, and other. Requires async mode. Stem failure does not fail the generation."
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> stems;

    @Override
    protected Output execute(RunContext runContext, SoniloClient client) throws Exception {
        String rVideo = optionalText(runContext, video).orElse(null);
        String rVideoUrl = optionalText(runContext, videoUrl).orElse(null);
        requireExactlyOne("video", rVideo, "videoUrl", rVideoUrl);
        OutputFormat rFormat = optionalEnum(runContext, outputFormat, OutputFormat.class).orElse(null);
        Integer rVariants = optionalInteger(runContext, variantsNum).orElse(null);
        if (rVariants != null) {
            requireRange("variantsNum", rVariants, 1, 10);
        }
        Optional<Double> rInfluence = optionalDouble(runContext, promptInfluence);
        rInfluence.ifPresent(value -> requireRange("promptInfluence", value, 0, 1));
        Optional<Boolean> rSpeech = optionalBoolean(runContext, preserveSpeech);
        Optional<Boolean> rDucking = optionalBoolean(runContext, ducking);
        Optional<Boolean> rStems = optionalBoolean(runContext, stems);
        boolean asyncOptions = SoniloSupport.needsAsync(
            rFormat == null ? null : rFormat.name(),
            rVariants,
            rStems.orElse(null),
            rDucking.orElse(null),
            rSpeech.orElse(null)
        );
        Optional<Mode> rMode = optionalEnum(runContext, mode, Mode.class);
        if (rMode.isEmpty() && asyncOptions) {
            runContext.logger().info("Sonilo music options require async mode; sending mode=async.");
        }
        String selected = SoniloSupport.selectMode(rMode.map(Enum::name).orElse(null), asyncOptions);

        Map<String, Object> form = new LinkedHashMap<>();
        if (rVideo != null) {
            form.put("video", client.storageFile(rVideo, "video.mp4").file());
        } else {
            form.put("video_url", rVideoUrl);
        }
        optionalText(runContext, prompt).ifPresent(value -> form.put("prompt", value));
        optionalText(runContext, segments).ifPresent(value -> form.put("segments", value));
        form.put("mode", selected);
        if (rFormat != null) {
            form.put("output_format", rFormat.name());
        }
        rSpeech.ifPresent(value -> form.put("preserve_speech", Boolean.toString(value)));
        rDucking.ifPresent(value -> form.put("ducking", Boolean.toString(value)));
        rInfluence.ifPresent(value -> form.put("prompt_influence", SoniloSupport.formatNumber(value)));
        if (rVariants != null) {
            form.put("variants_num", SoniloSupport.formatNumber(rVariants));
        }
        rStems.ifPresent(value -> form.put("stems", Boolean.toString(value)));
        return Output.from(client.generate("/video-to-music", form, renderPollInterval(runContext), renderWaitTimeout(runContext)));
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

        @Schema(title = "Internal storage URIs of the clean audio tracks, then any ducked mixes")
        private final List<URI> audioUris;

        @Schema(title = "Generated title")
        private final String title;

        @Schema(title = "MIME type of the primary file")
        private final String contentType;

        @Schema(title = "Internal storage URI of the ducked mix")
        private final URI duckedUri;

        @Schema(title = "Internal storage URIs of separated stems, keyed by stream index and stem name")
        private final Map<String, URI> stemUris;

        @Schema(title = "Stem separation warning")
        private final String stemsError;

        @Schema(title = "Internal storage URI of isolated source vocals")
        private final URI vocalsUri;

        @Schema(title = "Internal storage URIs of speech-preserving muxes")
        private final List<URI> muxUris;

        private static Output from(SoniloClient.Generation generation) {
            return Output.builder()
                .taskId(generation.taskId())
                .status(generation.status())
                .audioUri(generation.audioUri())
                .audioUris(generation.audioUris())
                .title(generation.title())
                .contentType(generation.contentType())
                .duckedUri(generation.duckedUri())
                .stemUris(generation.stemUris())
                .stemsError(generation.stemsError())
                .vocalsUri(generation.vocalsUri())
                .muxUris(generation.muxUris())
                .build();
        }
    }
}
