package io.kestra.plugin.sonilo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.plugin.sonilo.AbstractSonilo.Mode;
import io.kestra.plugin.sonilo.AbstractSonilo.Output;
import io.kestra.plugin.sonilo.AbstractSonilo.OutputFormat;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
public class GenerateMusicFromText extends AbstractSonilo {
    @Schema(
        title = "Music prompt",
        description = "Describes the music to generate. Sonilo documents a practical limit of 2000 characters."
    )
    @NotNull
    private Property<String> prompt;

    @Schema(
        title = "Duration in seconds",
        description = "Optional integer from 5 to 360. When omitted, Sonilo chooses the length."
    )
    private Property<Integer> duration;

    @Schema(
        title = "Generation mode",
        description = "stream returns NDJSON audio. async returns a task id and is required for wav, mp3, multiple variants, and stems. "
            + "When omitted, the task selects async if one of those options is set."
    )
    private Property<Mode> mode;

    @Schema(
        title = "Output format",
        description = "m4a, wav, or mp3. wav and mp3 require async mode. Streaming audio is always m4a."
    )
    private Property<OutputFormat> outputFormat;

    @Schema(
        title = "Number of variants",
        description = "Optional integer from 1 to 10. Values above 1 require async mode and are billed per variant."
    )
    private Property<Integer> variantsNum;

    @Schema(
        title = "Segment plan",
        description = "Optional JSON string of timed segment prompts. Sent as the segments form field."
    )
    private Property<String> segments;

    @Schema(
        title = "Also return separated stems",
        description = "When true, Sonilo adds drums, bass, vocals, and other stems. Requires async mode. Stem failure does not fail the generation."
    )
    private Property<Boolean> stems;

    @Override
    protected Output execute(RunContext runContext, SoniloClient client) throws Exception {
        String renderedPrompt = requiredText(runContext, prompt, "prompt");
        Integer renderedDuration = optionalInteger(runContext, duration).orElse(null);
        if (renderedDuration != null) {
            requireRange("duration", renderedDuration, 5, 360);
        }
        OutputFormat renderedFormat = optionalEnum(runContext, outputFormat, OutputFormat.class).orElse(null);
        Integer renderedVariants = optionalInteger(runContext, variantsNum).orElse(null);
        if (renderedVariants != null) {
            requireRange("variantsNum", renderedVariants, 1, 10);
        }
        Optional<Boolean> renderedStems = optionalBoolean(runContext, stems);
        boolean asyncOptions = SoniloSupport.needsAsync(
            renderedFormat == null ? null : renderedFormat.name(),
            renderedVariants,
            renderedStems.orElse(null),
            null,
            null
        );
        Optional<Mode> renderedMode = optionalEnum(runContext, mode, Mode.class);
        if (renderedMode.isEmpty() && asyncOptions) {
            runContext.logger().info("Sonilo music options require async mode; sending mode=async.");
        }
        String selected = SoniloSupport.selectMode(renderedMode.map(Enum::name).orElse(null), asyncOptions);

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("prompt", renderedPrompt);
        form.put("mode", selected);
        if (renderedDuration != null) {
            form.put("duration", SoniloSupport.formatNumber(renderedDuration));
        }
        if (renderedFormat != null) {
            form.put("output_format", renderedFormat.name());
        }
        if (renderedVariants != null) {
            form.put("variants_num", SoniloSupport.formatNumber(renderedVariants));
        }
        optionalText(runContext, segments).ifPresent(value -> form.put("segments", value));
        renderedStems.ifPresent(value -> form.put("stems", Boolean.toString(value)));
        return client.generate("/text-to-music", form, renderPollInterval(runContext), renderWaitTimeout(runContext));
    }
}
