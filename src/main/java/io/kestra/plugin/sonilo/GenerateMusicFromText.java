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
import jakarta.validation.constraints.NotNull;
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
    title = "Generate music from a text prompt",
    description = "Sends the prompt to POST /v1/text-to-music and stores the audio in Kestra internal storage. "
        + "Stream mode is the default. wav, mp3, multiple variants, and stems switch the request to async and poll GET /v1/tasks/{task_id}. "
        + "Killing the task stops the local wait only. Sonilo has no cancel endpoint, so remote work can continue and can still be billed."
)
@Plugin(
    examples = {
        @Example(
            title = "Generate a short music bed from a prompt",
            full = true,
            code = """
                id: generate_music_from_text
                namespace: company.media

                tasks:
                  - id: generate_music
                    type: io.kestra.plugin.sonilo.GenerateMusicFromText
                    apiToken: "{{ secret('SONILO_API_TOKEN') }}"
                    prompt: "Warm analog synths under a quiet piano"
                    duration: 30
                """
        )
    }
)
public class GenerateMusicFromText extends AbstractSonilo<GenerateMusicFromText.Output> implements RunnableTask<GenerateMusicFromText.Output> {
    @Schema(
        title = "Music prompt",
        description = "Describes the music to generate. Sonilo documents a practical limit of 2000 characters."
    )
    @PluginProperty(group = "processing")
    @NotNull
    private Property<String> prompt;

    @Schema(
        title = "Duration in seconds",
        description = "Optional integer from 5 to 360. When omitted, Sonilo chooses the length."
    )
    @PluginProperty(group = "processing")
    private Property<Integer> duration;

    @Schema(
        title = "Generation mode",
        description = "stream returns NDJSON audio. async returns a task id and is required for wav, mp3, multiple variants, and stems. "
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
        title = "Number of variants",
        description = "Optional integer from 1 to 10. Values above 1 require async mode and are billed per variant."
    )
    @PluginProperty(group = "processing")
    private Property<Integer> variantsNum;

    @Schema(
        title = "Segment plan",
        description = "Optional JSON string of timed segment prompts. Sent as the segments form field."
    )
    @PluginProperty(group = "processing")
    private Property<String> segments;

    @Schema(
        title = "Also return separated stems",
        description = "When true, Sonilo adds drums, bass, vocals, and other stems. Requires async mode. Stem failure does not fail the generation."
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> stems;

    @Override
    protected Output execute(RunContext runContext, SoniloClient client) throws Exception {
        String rPrompt = requiredText(runContext, prompt, "prompt");
        Integer rDuration = optionalInteger(runContext, duration).orElse(null);
        if (rDuration != null) {
            requireRange("duration", rDuration, 5, 360);
        }
        OutputFormat rFormat = optionalEnum(runContext, outputFormat, OutputFormat.class).orElse(null);
        Integer rVariants = optionalInteger(runContext, variantsNum).orElse(null);
        if (rVariants != null) {
            requireRange("variantsNum", rVariants, 1, 10);
        }
        Optional<Boolean> rStems = optionalBoolean(runContext, stems);
        boolean asyncOptions = SoniloSupport.needsAsync(
            rFormat == null ? null : rFormat.name(),
            rVariants,
            rStems.orElse(null),
            null,
            null
        );
        Optional<Mode> rMode = optionalEnum(runContext, mode, Mode.class);
        if (rMode.isEmpty() && asyncOptions) {
            runContext.logger().info("Sonilo music options require async mode; sending mode=async.");
        }
        String selected = SoniloSupport.selectMode(rMode.map(Enum::name).orElse(null), asyncOptions);

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("prompt", rPrompt);
        form.put("mode", selected);
        if (rDuration != null) {
            form.put("duration", SoniloSupport.formatNumber(rDuration));
        }
        if (rFormat != null) {
            form.put("output_format", rFormat.name());
        }
        if (rVariants != null) {
            form.put("variants_num", SoniloSupport.formatNumber(rVariants));
        }
        optionalText(runContext, segments).ifPresent(value -> form.put("segments", value));
        rStems.ifPresent(value -> form.put("stems", Boolean.toString(value)));
        return Output.from(client.generate("/text-to-music", form, renderPollInterval(runContext), renderWaitTimeout(runContext)));
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

        @Schema(title = "Internal storage URIs of separated stems, keyed by stream index and stem name")
        private final Map<String, URI> stemUris;

        @Schema(title = "Stem separation warning")
        private final String stemsError;

        private static Output from(SoniloClient.Generation generation) {
            return Output.builder()
                .taskId(generation.taskId())
                .status(generation.status())
                .audioUri(generation.audioUri())
                .audioUris(generation.audioUris())
                .title(generation.title())
                .contentType(generation.contentType())
                .stemUris(generation.stemUris())
                .stemsError(generation.stemsError())
                .build();
        }
    }
}
