package io.kestra.plugin.sonilo;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValueAndMetadata;
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
    title = "Watch a Sonilo task until it finishes",
    description = "Polls GET /v1/tasks/{taskId} once per interval. Sonilo does not offer a webhook. "
        + "When the task reaches a terminal status, the trigger starts one execution. Success and failure both fire. "
        + "A successful task's primary file is copied into Kestra internal storage because presigned URLs expire. "
        + "A failed task does not download a file and does not fail the trigger."
)
@Plugin(
    examples = {
        @Example(
            title = "Run a flow when a Sonilo task finishes",
            full = true,
            code = """
                id: on_sonilo_task_complete
                namespace: company.media

                tasks:
                  - id: log_status
                    type: io.kestra.plugin.core.log.Log
                    message: "Sonilo task {{ trigger.taskId }} finished: {{ trigger.status }}"

                triggers:
                  - id: wait_for_task
                    type: io.kestra.plugin.sonilo.Trigger
                    apiToken: "{{ secret('SONILO_API_TOKEN') }}"
                    interval: PT30S
                    taskId: "6f1c2e5a-9b3d-4e7a-8c1d-2a4b6c8d0e1f"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, SoniloConnection {
    @Schema(
        title = "Sonilo API token",
        description = "Bearer token from a Sonilo Platform account at platform.sonilo.com. "
            + "Platform keys are separate from sonilo.com app logins. Sent as Authorization: Bearer."
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    private Property<String> apiToken;

    @Schema(
        title = "Sonilo API base URL",
        description = "API host root. Defaults to https://api.sonilo.com. A trailing slash, or a base that already ends in /v1, is accepted."
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> baseUrl = Property.ofValue(SoniloSupport.DEFAULT_BASE_URL);

    @Schema(
        title = "HTTP client configuration",
        description = "Optional proxy, timeout, and TLS settings for calls to the Sonilo API."
    )
    @PluginProperty(group = "advanced")
    private HttpConfiguration options;

    @Schema(
        title = "Interval between polling",
        description = "Time between status checks. Use at least PT30S. Each evaluation sends one GET /v1/tasks/{taskId}."
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Duration interval = Duration.ofSeconds(30);

    @Schema(
        title = "Sonilo task identifier to watch",
        description = "Existing task id returned by an earlier Sonilo submission."
    )
    @NotNull
    private Property<String> taskId;

    private static final int FIRE_LOCKS = 64;

    private static final Object[] FIRE_LOCK = new Object[FIRE_LOCKS];

    static {
        for (int index = 0; index < FIRE_LOCKS; index++) {
            FIRE_LOCK[index] = new Object();
        }
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        String renderedTaskId = runContext.render(taskId).as(String.class).orElse("").trim();
        if (renderedTaskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        SoniloClient client = new SoniloClient(runContext, this, new AbstractSonilo.RunState());
        Map<String, Object> task = client.fetchTask(renderedTaskId);
        String rawStatus = task.get("status") == null ? "" : task.get("status").toString().trim();
        String status = SoniloSupport.statusOf(task);
        if (!SoniloSupport.isTerminal(status)) {
            runContext.logger().debug("Sonilo task {} is {}", renderedTaskId, rawStatus);
            return Optional.empty();
        }

        String key = SoniloSupport.kvKey(flowId(conditionContext, context, runContext), getId(), renderedTaskId);
        if (alreadyFired(runContext, conditionContext, key, status)) {
            runContext.logger().debug("Sonilo task {} already fired with status {}", renderedTaskId, status);
            return Optional.empty();
        }

        // Build the output before the claim. A download failure must leave the key unset so the next interval retries.
        Output output = toOutput(runContext, client, task, renderedTaskId, rawStatus, status);
        Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);
        // The scheduler loop is one thread and skips a trigger while evaluateRunningDate is set.
        // A scheduler restart clears that date while a separate worker can still be inside evaluate().
        // Namespace KV put is not a compare-and-set, so same-JVM evaluations claim under this lock.
        // Two worker processes can still both emit; there is no plugin-facing distributed lock.
        synchronized (fireLock(conditionContext, runContext, key)) {
            if (alreadyFired(runContext, conditionContext, key, status)) {
                runContext.logger().debug("Sonilo task {} already fired with status {}", renderedTaskId, status);
                return Optional.empty();
            }
            if (!markFired(runContext, conditionContext, key, status)) {
                return Optional.empty();
            }
        }
        runContext.logger().info("Sonilo task {} finished with status {}", renderedTaskId, status);
        return Optional.of(execution);
    }

    private Output toOutput(RunContext runContext, SoniloClient client, Map<String, Object> task, String renderedTaskId, String rawStatus, String status) throws Exception {
        String responseTaskId = task.get("task_id") == null ? renderedTaskId : task.get("task_id").toString();
        if (SoniloSupport.isFailure(status)) {
            String code = null;
            String message = null;
            Object error = task.get("error");
            if (error instanceof String text) {
                message = text;
            } else if (error instanceof Map<?, ?> raw) {
                if (raw.get("code") != null) {
                    code = raw.get("code").toString();
                }
                if (raw.get("message") != null) {
                    message = raw.get("message").toString();
                }
            }
            return Output.builder()
                .taskId(responseTaskId)
                .status(rawStatus)
                .error(message)
                .errorCode(code)
                .build();
        }

        String remoteUrl = client.primaryUrl(task);
        URI audioUri = client.storePrimary(task);
        return Output.builder()
            .taskId(responseTaskId)
            .status(rawStatus)
            .audioUrl(remoteUrl)
            .outputUrl(task.get("output_url") == null ? null : task.get("output_url").toString())
            .audioUri(audioUri)
            .build();
    }

    private boolean alreadyFired(RunContext runContext, ConditionContext conditionContext, String key, String status) {
        try {
            KVStore store = kvStore(runContext, conditionContext);
            if (store == null) {
                return false;
            }
            var existing = store.getValue(key);
            if (existing.isEmpty() || existing.get().value() == null) {
                return false;
            }
            return status.equals(statusText(existing.get().value()));
        } catch (Exception exception) {
            runContext.logger().warn("Sonilo trigger could not read its namespace state", exception);
            return false;
        }
    }

    /**
     * @return false when the state write failed and this evaluation must not emit an execution
     */
    private boolean markFired(RunContext runContext, ConditionContext conditionContext, String key, String status) {
        KVStore store;
        try {
            store = kvStore(runContext, conditionContext);
        } catch (Exception exception) {
            runContext.logger().warn("Sonilo trigger has no namespace KV; this completion may be emitted again", exception);
            return true;
        }
        if (store == null) {
            runContext.logger().warn("Sonilo trigger has no namespace KV; this completion may be emitted again");
            return true;
        }
        try {
            store.put(key, new KVValueAndMetadata(new KVMetadata("Sonilo trigger terminal status", (Duration) null), status.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return true;
        } catch (Exception exception) {
            runContext.logger().warn("Sonilo trigger could not store terminal state; retrying on the next interval", exception);
            return false;
        }
    }

    private String flowId(ConditionContext conditionContext, TriggerContext context, RunContext runContext) {
        if (context.getFlowId() != null && !context.getFlowId().isBlank()) {
            return context.getFlowId();
        }
        try {
            var info = runContext.flowInfo();
            if (info != null && info.id() != null && !info.id().isBlank()) {
                return info.id();
            }
        } catch (RuntimeException exception) {
            runContext.logger().debug("Sonilo trigger could not read flow info", exception);
        }
        if (conditionContext.getFlow() != null && conditionContext.getFlow().getId() != null) {
            return conditionContext.getFlow().getId();
        }
        return "flow";
    }

    private Object fireLock(ConditionContext conditionContext, RunContext runContext, String key) {
        String tenant = "";
        if (conditionContext.getFlow() != null && conditionContext.getFlow().getTenantId() != null) {
            tenant = conditionContext.getFlow().getTenantId();
        }
        String namespace = namespace(runContext, conditionContext);
        String claim = tenant + "\0" + (namespace == null ? "" : namespace) + "\0" + key;
        return FIRE_LOCK[Math.floorMod(claim.hashCode(), FIRE_LOCKS)];
    }

    private KVStore kvStore(RunContext runContext, ConditionContext conditionContext) {
        String namespace = namespace(runContext, conditionContext);
        if (namespace == null) {
            return null;
        }
        return runContext.namespaceKv(namespace);
    }

    private String namespace(RunContext runContext, ConditionContext conditionContext) {
        try {
            var info = runContext.flowInfo();
            if (info != null && info.namespace() != null && !info.namespace().isBlank()) {
                return info.namespace();
            }
        } catch (RuntimeException exception) {
            runContext.logger().debug("Sonilo trigger could not read flow info", exception);
        }
        if (conditionContext.getFlow() != null && conditionContext.getFlow().getNamespace() != null && !conditionContext.getFlow().getNamespace().isBlank()) {
            return conditionContext.getFlow().getNamespace();
        }
        return null;
    }

    private static String statusText(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
            return trimmed;
        }
        return value == null ? "" : value.toString();
    }

    @Builder
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Sonilo task identifier")
        private final String taskId;

        @Schema(title = "Sonilo task status")
        private final String status;

        @Schema(title = "Remote URL of the primary media file")
        private final String audioUrl;

        @Schema(title = "Remote output URL for a ducked mix")
        private final String outputUrl;

        @Schema(title = "Failure message")
        private final String error;

        @Schema(title = "Failure code")
        private final String errorCode;

        @Schema(title = "Internal storage URI of the primary file")
        private final URI audioUri;
    }
}
