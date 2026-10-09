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
public class GenerateSfxFromText extends AbstractSonilo<GenerateSfxFromText.Output> implements RunnableTask<GenerateSfxFromText.Output> {
    @Schema(
        title = "Sound effect prompt",
        description = "Describes the effect to generate. Sonilo documents a practical limit of 2000 characters."
    )
    @PluginProperty(group = "processing")
    @NotNull
    private Property<String> prompt;

    @Schema(
        title = "Duration in seconds",
        description = "Optional length from 0.5 to 180. When omitted, Sonilo chooses a length."
    )
    @PluginProperty(group = "processing")
    private Property<Double> duration;

    @Schema(
        title = "Audio format",
        description = "Optional container such as mp3, wav, or m4a. Sent as audio_format."
    )
    @PluginProperty(group = "processing")
    private Property<String> audioFormat;

    @Override
    protected Output execute(RunContext runContext, SoniloClient client) throws Exception {
        String rPrompt = requiredText(runContext, prompt, "prompt");
        Double rDuration = optionalDouble(runContext, duration).orElse(null);
        if (rDuration != null) {
            requireRange("duration", rDuration, 0.5, 180);
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("prompt", rPrompt);
        if (rDuration != null) {
            form.put("duration", SoniloSupport.formatNumber(rDuration));
        }
        optionalText(runContext, audioFormat).ifPresent(value -> form.put("audio_format", value));
        return Output.from(client.generate("/text-to-sfx", form, renderPollInterval(runContext), renderWaitTimeout(runContext)));
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
