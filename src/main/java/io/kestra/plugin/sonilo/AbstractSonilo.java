package io.kestra.plugin.sonilo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
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
public abstract class AbstractSonilo<O extends io.kestra.core.models.tasks.Output> extends Task implements RunnableTask<O>, SoniloConnection {
    // Kill and stop apply to this instance. A deserialized copy does not see the run.
    @Hidden
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    private transient volatile RunState $runState;

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
        title = "Time between Sonilo task status checks",
        description = "Used after an async submission while waiting for GET /v1/tasks/{task_id}. Defaults to PT3S."
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<Duration> pollInterval = Property.ofValue(Duration.ofSeconds(3));

    @Schema(
        title = "Maximum time to wait for a Sonilo task",
        description = "Async generation, including stem separation, is polled until this timeout. Defaults to PT30M. "
            + "Stem separation often finishes in a few minutes and can run much longer."
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<Duration> waitTimeout = Property.ofValue(Duration.ofMinutes(30));

    @Override
    public O run(RunContext runContext) throws Exception {
        RunState state = state();
        state.thread = Thread.currentThread();
        try {
            if (state.killed.get()) {
                throw killedException();
            }
            SoniloClient client = new SoniloClient(runContext, this, state);
            O output = execute(runContext, client);
            if (state.killed.get()) {
                throw killedException();
            }
            return output;
        } finally {
            state.thread = null;
            HttpClient httpClient = state.client.getAndSet(null);
            if (httpClient != null) {
                closeQuietly(httpClient);
            }
            if (state.killed.get()) {
                Thread.interrupted();
            }
        }
    }

    protected abstract O execute(RunContext runContext, SoniloClient client) throws Exception;

    /**
     * Sonilo has no cancel API. This stops the local HTTP call and the local poll.
     * Work Sonilo already accepted can keep running and can still be billed.
     */
    @Override
    public void kill() {
        RunState state = state();
        state.killed.set(true);
        abort(state.client.get());
        Thread worker = state.thread;
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public void stop() {
        state().killed.set(true);
    }

    private RunState state() {
        RunState current = $runState;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if ($runState == null) {
                $runState = new RunState();
            }
            return $runState;
        }
    }

    protected Duration renderPollInterval(RunContext runContext) throws Exception {
        Duration interval = pollInterval == null
            ? Duration.ofSeconds(3)
            : runContext.render(pollInterval).as(Duration.class).orElse(Duration.ofSeconds(3));
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        return interval;
    }

    protected Duration renderWaitTimeout(RunContext runContext) throws Exception {
        Duration timeout = waitTimeout == null
            ? Duration.ofMinutes(30)
            : runContext.render(waitTimeout).as(Duration.class).orElse(Duration.ofMinutes(30));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("waitTimeout must be positive");
        }
        return timeout;
    }

    protected Optional<String> optionalText(RunContext runContext, Property<String> property) throws Exception {
        if (property == null) {
            return Optional.empty();
        }
        return runContext.render(property).as(String.class).map(String::trim).filter(value -> !value.isBlank());
    }

    protected String requiredText(RunContext runContext, Property<String> property, String name) throws Exception {
        return optionalText(runContext, property).orElseThrow(() -> new IllegalArgumentException(name + " is required"));
    }

    protected Optional<Boolean> optionalBoolean(RunContext runContext, Property<Boolean> property) throws Exception {
        if (property == null) {
            return Optional.empty();
        }
        return runContext.render(property).as(Boolean.class);
    }

    protected Optional<Integer> optionalInteger(RunContext runContext, Property<Integer> property) throws Exception {
        if (property == null) {
            return Optional.empty();
        }
        return runContext.render(property).as(Integer.class);
    }

    protected Optional<Double> optionalDouble(RunContext runContext, Property<Double> property) throws Exception {
        if (property == null) {
            return Optional.empty();
        }
        return runContext.render(property).as(Double.class);
    }

    protected <E extends Enum<E>> Optional<E> optionalEnum(RunContext runContext, Property<E> property, Class<E> type) throws Exception {
        if (property == null) {
            return Optional.empty();
        }
        return runContext.render(property).as(type);
    }

    protected void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
    }

    protected void requireRange(String name, double value, double min, double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                name + " must be between " + SoniloSupport.formatNumber(min) + " and " + SoniloSupport.formatNumber(max)
            );
        }
    }

    protected void requireExactlyOne(String leftName, String left, String rightName, String right) {
        boolean hasLeft = left != null && !left.isBlank();
        boolean hasRight = right != null && !right.isBlank();
        if (hasLeft == hasRight) {
            throw new IllegalArgumentException("Provide exactly one of " + leftName + " or " + rightName);
        }
    }

    private static CancellationException killedException() {
        return new CancellationException("Sonilo task was killed");
    }

    /**
     * {@code HttpClient.close()} waits for the active call. Immediate close unblocks that call.
     * Sonilo does not expose a remote cancel endpoint.
     */
    private static void abort(HttpClient httpClient) {
        if (httpClient == null) {
            return;
        }
        try {
            Field field = HttpClient.class.getDeclaredField("client");
            field.setAccessible(true);
            Object apache = field.get(httpClient);
            if (apache == null) {
                closeQuietly(httpClient);
                return;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Enum<?>> closeMode = (Class<? extends Enum<?>>) Class.forName("org.apache.hc.core5.io.CloseMode");
            Object immediate = Enum.valueOf(closeMode.asSubclass(Enum.class), "IMMEDIATE");
            Method close = apache.getClass().getMethod("close", closeMode);
            close.invoke(apache, immediate);
        } catch (Exception ignored) {
            closeQuietly(httpClient);
        }
    }

    private static void closeQuietly(HttpClient httpClient) {
        try {
            httpClient.close();
        } catch (Exception ignored) {
        }
    }

    static final class RunState {
        final AtomicBoolean killed = new AtomicBoolean();
        final AtomicReference<HttpClient> client = new AtomicReference<>();
        volatile Thread thread;

        void checkKilled() {
            if (killed.get()) {
                throw killedException();
            }
        }
    }

    public enum Mode {
        stream,
        async
    }

    public enum OutputFormat {
        m4a,
        wav,
        mp3
    }
}
