package io.kestra.plugin.sonilo;

import java.util.LinkedHashMap;
import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.sonilo.AbstractSonilo.Output;
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
    title = "Generate sound effects from a text prompt",
    description = "Submits POST /v1/text-to-sfx, polls GET /v1/tasks/{task_id}, and stores the audio in Kestra internal storage. "
        + "Sound-effect generation is always asynchronous. Killing the task stops the local wait only. Sonilo has no cancel endpoint, so remote work can continue and can still be billed."
)
@Plugin(
    examples = {
        @Example(
            title = "Generate footsteps and log the stored file",
            full = true,
            code = """
                id: generate_sfx
                namespace: company.media

                tasks:
                  - id: generate_sfx
                    type: io.kestra.plugin.sonilo.GenerateSfxFromText
                    apiToken: "{{ secret('SONILO_API_TOKEN') }}"
                    prompt: "Footsteps on gravel, approaching camera"
                  - id: log_sfx
                    type: io.kestra.plugin.core.log.Log
                    message: "Generated SFX available at {{ outputs.generate_sfx.audioUri }}"
                """
        )
    }
)
public class GenerateSfxFromText extends AbstractSonilo {
    @Schema(
        title = "Sound effect prompt",
        description = "Describes the effect to generate. Sonilo documents a practical limit of 2000 characters."
    )
    @NotNull
    private Property<String> prompt;

    @Schema(
        title = "Duration in seconds",
        description = "Optional length from 0.5 to 180. When omitted, Sonilo chooses a length."
    )
    private Property<Double> duration;

    @Schema(
        title = "Audio format",
        description = "Optional container such as mp3, wav, or m4a. Sent as audio_format."
    )
    private Property<String> audioFormat;

    @Override
    protected Output execute(RunContext runContext, SoniloClient client) throws Exception {
        String renderedPrompt = requiredText(runContext, prompt, "prompt");
        Double renderedDuration = optionalDouble(runContext, duration).orElse(null);
        if (renderedDuration != null) {
            requireRange("duration", renderedDuration, 0.5, 180);
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("prompt", renderedPrompt);
        if (renderedDuration != null) {
            form.put("duration", SoniloSupport.formatNumber(renderedDuration));
        }
        optionalText(runContext, audioFormat).ifPresent(value -> form.put("audio_format", value));
        return client.generate("/text-to-sfx", form, renderPollInterval(runContext), renderWaitTimeout(runContext));
    }
}
