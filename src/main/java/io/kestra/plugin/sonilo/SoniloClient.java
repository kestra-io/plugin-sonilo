package io.kestra.plugin.sonilo;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

/**
 * HTTP access to api.sonilo.com through Kestra's HttpClient.
 * Presigned media URLs are fetched without the bearer token because the CDN rejects extra authorization.
 */
final class SoniloClient {
    private static final Duration KILL_SLICE = Duration.ofMillis(100);
    private static final List<String> STEM_NAMES = List.of("drums", "bass", "vocals", "other");

    private final RunContext runContext;
    private final AbstractSonilo.RunState state;
    private final String token;
    private final String baseUrl;
    private final HttpConfiguration options;

    SoniloClient(RunContext runContext, SoniloConnection connection, AbstractSonilo.RunState state) throws Exception {
        this.runContext = runContext;
        this.state = state;
        if (connection.getApiToken() == null) {
            throw new IllegalArgumentException("apiToken is required");
        }
        String renderedToken = runContext.render(connection.getApiToken()).as(String.class).orElse("").trim();
        if (renderedToken.isBlank()) {
            throw new IllegalArgumentException("apiToken is required");
        }
        this.token = renderedToken;
        String renderedBase = connection.getBaseUrl() == null
            ? SoniloSupport.DEFAULT_BASE_URL
            : runContext.render(connection.getBaseUrl()).as(String.class).orElse(SoniloSupport.DEFAULT_BASE_URL);
        this.baseUrl = SoniloSupport.normalizeBase(renderedBase);
        this.options = connection.getOptions();
    }

    AbstractSonilo.Output generate(String path, Map<String, Object> form, Duration pollInterval, Duration waitTimeout) throws Exception {
        state.checkKilled();
        runContext.logger().info("Submitting Sonilo request to {}", path);
        runContext.logger().debug("Sonilo form fields {}", form.keySet());
        ParsedBody body = post(path, form);
        if (body.task() != null && body.capture() == null) {
            return awaitIfNeeded(body.task(), pollInterval, waitTimeout);
        }
        return storeStream(body.capture());
    }

    Map<String, Object> fetchTask(String taskId) throws Exception {
        String path = SoniloSupport.taskPath(taskId);
        runContext.logger().debug("Checking Sonilo task {}", taskId);
        return getJson(path);
    }

    URI storePrimary(Map<String, Object> task) throws Exception {
        StoredMedia stored = storeAll(task, true);
        if (stored.audioUri() == null) {
            throw new IllegalStateException("Sonilo task " + string(task.get("task_id")) + " succeeded without a downloadable file");
        }
        return stored.audioUri();
    }

    String primaryUrl(Map<String, Object> task) {
        List<Media> audio = mediaList(task.get("audio"));
        if (!audio.isEmpty()) {
            return audio.getFirst().url();
        }
        String outputUrl = string(task.get("output_url"));
        if (outputUrl != null) {
            return outputUrl;
        }
        List<Media> ducked = mediaList(task.get("ducked"));
        if (!ducked.isEmpty()) {
            return ducked.getFirst().url();
        }
        return null;
    }

    FileUpload storageFile(String uriValue, String fallbackName) throws IOException {
        URI uri = URI.create(uriValue);
        String name = SoniloSupport.fileName(uri, fallbackName);
        Path path = runContext.workingDir().createFile(name);
        try (InputStream input = runContext.storage().getFile(uri)) {
            if (input == null) {
                throw new IllegalArgumentException("Internal storage file " + name + " could not be read");
            }
            Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.size(path) == 0) {
            throw new IllegalArgumentException("Internal storage file " + name + " is empty");
        }
        return new FileUpload(path.toFile());
    }

    private AbstractSonilo.Output awaitIfNeeded(Map<String, Object> task, Duration pollInterval, Duration waitTimeout) throws Exception {
        String status = SoniloSupport.statusOf(task);
        String taskId = string(task.get("task_id"));
        if (SoniloSupport.isFailure(status)) {
            throw failure(task);
        }
        if (taskId != null && !SoniloSupport.isTerminal(status)) {
            task = poll(taskId, pollInterval, waitTimeout);
            status = SoniloSupport.statusOf(task);
        }
        if (SoniloSupport.isFailure(status)) {
            throw failure(task);
        }
        if (taskId == null && !hasDownload(task)) {
            throw new IllegalStateException("Sonilo response did not include audio or a task_id");
        }
        if (!SoniloSupport.isSuccess(status) && !hasDownload(task)) {
            throw new IllegalStateException("Sonilo task " + taskId + " ended with status " + status);
        }
        return storeAll(task, false).toOutput(SoniloSupport.isSuccess(status) ? status : "succeeded");
    }

    private Map<String, Object> poll(String taskId, Duration pollInterval, Duration waitTimeout) throws Exception {
        Instant deadline = Instant.now().plus(waitTimeout);
        while (true) {
            state.checkKilled();
            Map<String, Object> task = fetchTask(taskId);
            String status = SoniloSupport.statusOf(task);
            runContext.logger().debug("Sonilo task {} is {}", taskId, status);
            if (SoniloSupport.isTerminal(status)) {
                return task;
            }
            Instant now = Instant.now();
            if (!now.isBefore(deadline)) {
                throw new IllegalStateException("Sonilo task " + taskId + " did not finish within " + waitTimeout);
            }
            Duration remaining = Duration.between(now, deadline);
            pause(remaining.compareTo(pollInterval) < 0 ? remaining : pollInterval);
        }
    }

    private ParsedBody post(String path, Map<String, Object> form) throws Exception {
        HttpRequest request = authorized("POST", SoniloSupport.endpoint(baseUrl, path))
            .body(HttpRequest.MultipartRequestBody.of(form))
            .addHeader("Accept", "application/json, application/x-ndjson")
            .build();
        ParsedBody[] parsed = new ParsedBody[1];
        exchange(request, response -> parsed[0] = readBody(response));
        if (parsed[0] == null) {
            throw new IllegalStateException("Sonilo returned an empty response");
        }
        return parsed[0];
    }

    private Map<String, Object> getJson(String path) throws Exception {
        HttpRequest request = authorized("GET", SoniloSupport.endpoint(baseUrl, path))
            .addHeader("Accept", "application/json")
            .build();
        String[] raw = new String[1];
        exchange(request, response -> {
            try {
                raw[0] = readText(response.getBody());
            } catch (IOException exception) {
                throw new IllegalStateException("Sonilo returned a response that could not be read", exception);
            }
        });
        return jsonMap(raw[0]);
    }

    private HttpRequest.HttpRequestBuilder authorized(String method, String url) {
        return HttpRequest.builder()
            .method(method)
            .uri(URI.create(url))
            .addHeader("Authorization", "Bearer " + token);
    }

    private void exchange(HttpRequest request, java.util.function.Consumer<HttpResponse<InputStream>> consumer) throws Exception {
        int rateLimitRetries = 0;
        int gatewayRetries = 0;
        while (true) {
            state.checkKilled();
            HttpClient httpClient = open();
            try {
                httpClient.request(request, consumer);
                state.checkKilled();
                return;
            } catch (Exception exception) {
                if (state.killed.get()) {
                    throw new CancellationException("Sonilo task was killed");
                }
                HttpClientResponseException responseException = responseException(exception);
                if (responseException != null) {
                    int code = statusCode(responseException);
                    if (code == 429 && rateLimitRetries < 3) {
                        rateLimitRetries++;
                        runContext.logger().info("Sonilo rate limit reached; retrying the request");
                        pause(retryAfter(responseException, rateLimitRetries));
                        continue;
                    }
                    if (code == 502 && gatewayRetries < 2) {
                        gatewayRetries++;
                        runContext.logger().info("Sonilo returned 502; retrying the request");
                        pause(Duration.ofSeconds(1L << gatewayRetries));
                        continue;
                    }
                    throw apiException(responseException);
                }
                throw exception;
            } finally {
                state.client.compareAndSet(httpClient, null);
                try {
                    httpClient.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private HttpClient open() throws Exception {
        state.checkKilled();
        HttpClient httpClient = new HttpClient(runContext, options);
        disableAutomaticRetries(httpClient);
        state.client.set(httpClient);
        if (state.killed.get()) {
            httpClient.close();
            throw new CancellationException("Sonilo task was killed");
        }
        return httpClient;
    }

    /**
     * Kestra 1.3.39 {@code HttpClient} and {@code HttpConfiguration} have no retry switch.
     * {@code createClient()} leaves Apache's {@code DefaultHttpRequestRetryStrategy}, which retries
     * 429 and 503 once after one second, including non-idempotent POSTs. That extra attempt ignores
     * Retry-After and resubmits a request Sonilo treats as unavailable.
     * <p>
     * The strategy field on the retry handler is final, so this rebuilds the execution chain with
     * {@code HttpRequestRetryExec}'s public constructor and swaps Kestra's non-final client field.
     * The original client is not closed: its close list holds the same connection manager.
     * Only this class retries, and only 429 and 502.
     */
    private void disableAutomaticRetries(HttpClient httpClient) {
        try {
            Field clientField = HttpClient.class.getDeclaredField("client");
            clientField.setAccessible(true);
            Object apache = clientField.get(httpClient);
            if (apache == null) {
                return;
            }
            Class<?> retryExecType = Class.forName("org.apache.hc.client5.http.impl.classic.HttpRequestRetryExec");
            Class<?> strategyType = Class.forName("org.apache.hc.client5.http.HttpRequestRetryStrategy");
            Class<?> elementType = Class.forName("org.apache.hc.client5.http.impl.classic.ExecChainElement");
            Class<?> handlerType = Class.forName("org.apache.hc.client5.http.classic.ExecChainHandler");
            Object never = neverRetry(strategyType);

            List<Object> handlers = new ArrayList<>();
            boolean replaced = false;
            Object element = readField(apache, "execChain");
            while (element != null) {
                Object handler = readField(element, "handler");
                if (retryExecType.isInstance(handler)) {
                    handler = retryExecType.getConstructor(strategyType).newInstance(never);
                    replaced = true;
                }
                handlers.add(handler);
                element = readField(element, "next");
            }
            if (!replaced) {
                runContext.logger().warn("Could not disable Apache HttpClient automatic retries");
                return;
            }

            Constructor<?> elementCtor = elementType.getDeclaredConstructor(handlerType, elementType);
            elementCtor.setAccessible(true);
            Object rebuilt = null;
            for (int index = handlers.size() - 1; index >= 0; index--) {
                rebuilt = elementCtor.newInstance(handlers.get(index), rebuilt);
            }
            clientField.set(httpClient, newApacheClient(apache, rebuilt));
        } catch (Throwable exception) {
            runContext.logger().warn("Could not disable Apache HttpClient automatic retries: {}", exception.toString());
        }
    }

    /**
     * @param execChain rebuilt chain whose retry handler never retries
     */
    private static Object newApacheClient(Object apache, Object execChain) throws ReflectiveOperationException {
        Class<?> internalType = Class.forName("org.apache.hc.client5.http.impl.classic.InternalHttpClient");
        Class<?> elementType = Class.forName("org.apache.hc.client5.http.impl.classic.ExecChainElement");
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : internalType.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == 11) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) {
            throw new NoSuchMethodException("InternalHttpClient constructor");
        }
        constructor.setAccessible(true);

        List<Closeable> closeables = new ArrayList<>();
        Object existing = readField(apache, "closeables");
        if (existing instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Closeable closeable) {
                    closeables.add(closeable);
                }
            }
        }
        if (closeables.isEmpty() && readField(apache, "connManager") instanceof Closeable manager) {
            closeables.add(manager);
        }

        Object[] args = new Object[constructor.getParameterCount()];
        Class<?>[] rawTypes = constructor.getParameterTypes();
        Type[] genericTypes = constructor.getGenericParameterTypes();
        for (int index = 0; index < rawTypes.length; index++) {
            if (elementType.isAssignableFrom(rawTypes[index])) {
                args[index] = execChain;
            } else if (List.class.isAssignableFrom(rawTypes[index])) {
                args[index] = closeables;
            } else {
                args[index] = readField(apache, dependencyField(rawTypes[index], genericTypes[index]));
            }
        }
        return constructor.newInstance(args);
    }

    private static String dependencyField(Class<?> raw, Type generic) {
        if ("org.apache.hc.core5.http.config.Lookup".equals(raw.getName()) && generic instanceof ParameterizedType parameterized) {
            String argument = parameterized.getActualTypeArguments()[0].getTypeName();
            if (argument.contains("CookieSpecFactory")) {
                return "cookieSpecRegistry";
            }
            if (argument.contains("AuthSchemeFactory")) {
                return "authSchemeRegistry";
            }
        }
        return switch (raw.getName()) {
            case "org.apache.hc.client5.http.io.HttpClientConnectionManager" -> "connManager";
            case "org.apache.hc.core5.http.impl.io.HttpRequestExecutor" -> "requestExecutor";
            case "org.apache.hc.client5.http.routing.HttpRoutePlanner" -> "routePlanner";
            case "org.apache.hc.client5.http.cookie.CookieStore" -> "cookieStore";
            case "org.apache.hc.client5.http.auth.CredentialsProvider" -> "credentialsProvider";
            case "java.util.function.Function" -> "contextAdaptor";
            case "org.apache.hc.client5.http.config.RequestConfig" -> "defaultConfig";
            default -> throw new IllegalArgumentException("Unexpected Apache client dependency " + raw.getName());
        };
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object neverRetry(Class<?> strategyType) throws ReflectiveOperationException {
        Class<?> timeValue = Class.forName("org.apache.hc.core5.util.TimeValue");
        Object zero = timeValue.getField("ZERO_MILLISECONDS").get(null);
        return Proxy.newProxyInstance(strategyType.getClassLoader(), new Class<?>[] {strategyType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> "SoniloNeverRetry";
                };
            }
            if (method.getReturnType() == boolean.class) {
                return Boolean.FALSE;
            }
            if (timeValue.isAssignableFrom(method.getReturnType())) {
                return zero;
            }
            return null;
        });
    }

    private ParsedBody readBody(HttpResponse<InputStream> response) {
        String contentType = response.contentType() == null ? "" : response.contentType().toLowerCase(Locale.ROOT);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            if (contentType.contains("ndjson")) {
                return readNdjson(reader);
            }
            if (contentType.contains("json")) {
                String text = readText(reader);
                try {
                    return ParsedBody.task(jsonMap(text));
                } catch (IllegalStateException exception) {
                    return readNdjson(new BufferedReader(new StringReader(text)));
                }
            }
            return sniff(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Sonilo returned a response that could not be read", exception);
        }
    }

    private ParsedBody sniff(BufferedReader reader) throws IOException {
        String first = nextDataLine(reader);
        if (first == null) {
            throw new IllegalStateException("Sonilo returned an empty response");
        }
        Map<String, Object> object = jsonObjectOrNull(first);
        if (object != null && object.get("type") != null) {
            return readNdjson(reader, first);
        }
        if (object != null && (object.get("task_id") != null || object.get("status") != null || object.get("output_url") != null)) {
            String rest = readText(reader).strip();
            if (rest.isEmpty()) {
                return ParsedBody.task(object);
            }
            return readNdjson(new BufferedReader(new StringReader(first + "\n" + rest)));
        }
        String rest = readText(reader);
        String text = rest.isBlank() ? first : first + "\n" + rest;
        try {
            return ParsedBody.task(jsonMap(text));
        } catch (IllegalStateException exception) {
            return readNdjson(new BufferedReader(new StringReader(text)));
        }
    }

    private ParsedBody readNdjson(BufferedReader reader) throws IOException {
        return readNdjson(reader, null);
    }

    private ParsedBody readNdjson(BufferedReader reader, String firstLine) throws IOException {
        Map<Integer, OutputStream> open = new LinkedHashMap<>();
        Map<Integer, Path> paths = new TreeMap<>();
        long[] total = new long[1];
        String[] title = new String[1];
        String[] fallback = new String[1];
        boolean[] complete = new boolean[1];
        boolean[] taskAck = new boolean[1];
        Map<String, Object>[] ack = new Map[1];
        try {
            if (firstLine != null) {
                handleLine(firstLine, open, paths, total, title, fallback, complete, taskAck, ack);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                state.checkKilled();
                handleLine(line, open, paths, total, title, fallback, complete, taskAck, ack);
            }
        } catch (Exception exception) {
            closeStreams(open);
            if (exception instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(exception.getMessage(), exception);
        }
        closeStreams(open);
        if (taskAck[0] && ack[0] != null && total[0] == 0 && !complete[0]) {
            return ParsedBody.task(ack[0]);
        }
        if (!complete[0]) {
            throw new IllegalStateException("Sonilo stream ended before a complete event");
        }
        return ParsedBody.stream(new StreamCapture(title[0], paths, total[0], fallback[0]));
    }

    private void handleLine(
        String line,
        Map<Integer, OutputStream> open,
        Map<Integer, Path> paths,
        long[] total,
        String[] title,
        String[] fallback,
        boolean[] complete,
        boolean[] taskAck,
        Map<String, Object>[] ack
    ) throws IOException {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        Map<String, Object> event = jsonObjectOrNull(trimmed);
        if (event == null) {
            return;
        }
        Object typeValue = event.get("type");
        if (typeValue == null) {
            if (event.get("task_id") != null && total[0] == 0 && !complete[0]) {
                taskAck[0] = true;
                ack[0] = event;
            }
            return;
        }
        String type = typeValue.toString();
        runContext.logger().debug("Sonilo stream event {}", type);
        switch (type) {
            case "audio_chunk" -> writeChunk(event, open, paths, total);
            case "title" -> {
                String text = string(event.get("title"));
                if (text != null) {
                    title[0] = text;
                }
            }
            case "complete" -> {
                complete[0] = true;
                if (fallback[0] == null) {
                    fallback[0] = firstText(event, "url", "audio_url");
                }
            }
            case "error" -> throw new IllegalStateException(streamError(event));
            default -> {
                // cost and future event types carry no audio
            }
        }
    }

    private void writeChunk(Map<String, Object> event, Map<Integer, OutputStream> open, Map<Integer, Path> paths, long[] total) throws IOException {
        Object data = event.get("data");
        if (!(data instanceof String encoded)) {
            throw new IllegalStateException("Sonilo stream returned a malformed audio_chunk event (missing or non-decodable data)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Sonilo stream returned a malformed audio_chunk event (missing or non-decodable data)", exception);
        }
        int index = event.get("stream_index") instanceof Number number ? number.intValue() : 0;
        OutputStream output = open.get(index);
        if (output == null) {
            Path path = runContext.workingDir().createTempFile(".m4a");
            paths.put(index, path);
            output = Files.newOutputStream(path);
            open.put(index, output);
        }
        output.write(bytes);
        total[0] += bytes.length;
    }

    private AbstractSonilo.Output storeStream(StreamCapture capture) throws Exception {
        if (capture.totalBytes() == 0) {
            if (capture.fallbackUrl() != null) {
                Downloaded downloaded = download(capture.fallbackUrl(), "sonilo-stream.m4a", null, true);
                runContext.logger().info("Stored Sonilo audio ({} bytes)", downloaded.bytes());
                return AbstractSonilo.Output.builder()
                    .status("succeeded")
                    .audioUri(downloaded.uri())
                    .audioUris(List.of(downloaded.uri()))
                    .title(capture.title())
                    .contentType(downloaded.contentType())
                    .build();
            }
            throw new IllegalStateException("Sonilo stream completed without audio");
        }
        List<URI> uris = new ArrayList<>();
        String contentType = null;
        for (var entry : capture.paths().entrySet()) {
            long size = Files.size(entry.getValue());
            if (size == 0) {
                continue;
            }
            String name = "sonilo-stream-" + entry.getKey() + ".m4a";
            URI uri = runContext.storage().putFile(entry.getValue().toFile(), name);
            uris.add(uri);
            if (contentType == null) {
                contentType = "audio/mp4";
                runContext.logger().info("Stored Sonilo audio ({} bytes)", size);
            }
        }
        if (uris.isEmpty()) {
            throw new IllegalStateException("Sonilo stream completed without audio");
        }
        return AbstractSonilo.Output.builder()
            .status("succeeded")
            .audioUri(uris.getFirst())
            .audioUris(uris)
            .title(capture.title())
            .contentType(contentType)
            .build();
    }

    private StoredMedia storeAll(Map<String, Object> task, boolean primaryOnly) throws Exception {
        String taskId = string(task.get("task_id"));
        String prefix = taskId == null ? "sonilo" : "sonilo-" + SoniloSupport.sanitizeFilename(taskId, "task");
        List<Media> audio = mediaList(task.get("audio"));
        List<Media> ducked = mediaList(task.get("ducked"));
        String outputUrl = string(task.get("output_url"));
        String outputType = string(task.get("output_type"));
        List<URI> audioUris = new ArrayList<>();
        URI audioUri = null;
        String contentType = null;
        URI duckedUri = null;
        if (audio.isEmpty() && outputUrl != null) {
            Downloaded downloaded = download(outputUrl, prefix + "-output" + SoniloSupport.extensionFor(null, "video".equals(outputType) ? "mp4" : "wav", false), outputType, false);
            audioUri = downloaded.uri();
            audioUris.add(downloaded.uri());
            contentType = downloaded.contentType();
            runContext.logger().info("Stored Sonilo audio ({} bytes)", downloaded.bytes());
        } else {
            for (Media media : audio) {
                Downloaded downloaded = download(media.url(), prefix + "-audio-" + index(media) + extension(media, false), null, false);
                audioUris.add(downloaded.uri());
                if (audioUri == null) {
                    audioUri = downloaded.uri();
                    contentType = media.contentType() == null ? downloaded.contentType() : media.contentType();
                    runContext.logger().info("Stored Sonilo audio ({} bytes)", downloaded.bytes());
                }
                if (primaryOnly) {
                    break;
                }
            }
        }
        if (!primaryOnly) {
            for (Media media : ducked) {
                Downloaded downloaded = download(media.url(), prefix + "-ducked-" + index(media) + extension(media, false), null, false);
                if (duckedUri == null) {
                    duckedUri = downloaded.uri();
                }
                audioUris.add(downloaded.uri());
            }
        }
        Map<String, URI> stemUris = null;
        URI vocalsUri = null;
        List<URI> muxUris = null;
        if (!primaryOnly) {
            stemUris = storeStems(task.get("stems"), prefix);
            Media vocals = singleMedia(task.get("vocals"));
            if (vocals != null) {
                vocalsUri = download(vocals.url(), prefix + "-vocals" + extension(vocals, false), null, false).uri();
            }
            List<Media> mux = mediaList(task.get("mux"));
            if (!mux.isEmpty()) {
                muxUris = new ArrayList<>();
                for (Media media : mux) {
                    muxUris.add(download(media.url(), prefix + "-mux-" + index(media) + extension(media, false), null, false).uri());
                }
            }
        }
        if (audioUri == null && !primaryOnly) {
            throw new IllegalStateException("Sonilo task " + taskId + " succeeded without a downloadable file");
        }
        String title = titleOf(task, audio);
        return new StoredMedia(
            taskId,
            audioUri,
            audioUris.isEmpty() ? null : List.copyOf(audioUris),
            title,
            contentType,
            outputType,
            duckedUri,
            stemUris,
            string(task.get("stems_error")),
            vocalsUri,
            muxUris
        );
    }

    private Map<String, URI> storeStems(Object stems, String prefix) throws Exception {
        if (!(stems instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Map<String, URI> stored = new LinkedHashMap<>();
        for (Object item : list) {
            Map<String, Object> entry = asMap(item);
            if (entry == null) {
                continue;
            }
            int index = entry.get("stream_index") instanceof Number number ? number.intValue() : 0;
            for (String stem : STEM_NAMES) {
                Media media = singleMedia(entry.get(stem));
                if (media == null) {
                    continue;
                }
                URI uri = download(media.url(), prefix + "-stem-" + index + "-" + stem + extension(media, false), null, false).uri();
                stored.put(index + "." + stem, uri);
            }
        }
        return stored.isEmpty() ? null : stored;
    }

    private Downloaded download(String url, String filename, String outputType, boolean streamMusic) throws Exception {
        URI uri = resolve(url);
        runContext.logger().debug("Downloading Sonilo media from {}", uri.getHost());
        HttpRequest request = HttpRequest.builder().method("GET").uri(uri).build();
        Path temp = runContext.workingDir().createTempFile(".bin");
        String[] contentType = new String[1];
        long[] size = new long[1];
        exchange(request, response -> {
            contentType[0] = response.contentType();
            try (InputStream input = response.getBody(); OutputStream output = Files.newOutputStream(temp)) {
                if (input != null) {
                    size[0] = input.transferTo(output);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Sonilo media download failed", exception);
            }
        });
        if (size[0] == 0 || Files.size(temp) == 0) {
            throw new IllegalStateException("Sonilo returned an empty file from " + uri.getHost());
        }
        String extension = SoniloSupport.extensionFor(contentType[0], "video".equals(outputType) ? "mp4" : null, streamMusic);
        String name = filename;
        if (!".bin".equals(extension)) {
            int dot = filename.lastIndexOf('.');
            String baseName = dot > 0 ? filename.substring(0, dot) : filename;
            name = baseName + extension;
        }
        URI stored = runContext.storage().putFile(temp.toFile(), SoniloSupport.sanitizeFilename(name, "sonilo" + extension));
        String mime = contentType[0];
        if (mime != null && mime.contains(";")) {
            mime = mime.substring(0, mime.indexOf(';')).trim();
        }
        return new Downloaded(stored, size[0], mime);
    }

    private String extension(Media media, boolean streamMusic) {
        return SoniloSupport.extensionFor(media.contentType(), null, streamMusic);
    }

    private static String index(Media media) {
        return media.streamIndex() == null ? "0" : media.streamIndex().toString();
    }

    private URI resolve(String url) {
        URI parsed = URI.create(url.trim());
        if (parsed.isAbsolute()) {
            return parsed;
        }
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return URI.create(base).resolve(parsed);
    }

    private void pause(Duration duration) throws InterruptedException {
        long end = System.nanoTime() + Math.max(0, duration.toNanos());
        while (System.nanoTime() < end) {
            state.checkKilled();
            long slice = Math.min(KILL_SLICE.toNanos(), end - System.nanoTime());
            if (slice <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.max(1, Duration.ofNanos(slice).toMillis()));
            } catch (InterruptedException exception) {
                if (state.killed.get()) {
                    throw new CancellationException("Sonilo task was killed");
                }
                Thread.currentThread().interrupt();
                throw exception;
            }
        }
        state.checkKilled();
    }

    private static Duration retryAfter(HttpClientResponseException exception, int attempt) {
        Duration fallback = Duration.ofMillis(200L * attempt);
        HttpResponse<?> response = exception.getResponse();
        if (response == null || response.getHeaders() == null) {
            return fallback;
        }
        Optional<String> header = response.getHeaders().firstValue("Retry-After");
        if (header.isEmpty() || header.get().isBlank()) {
            return fallback;
        }
        String value = header.get().trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? fallback : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                Instant when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(Instant.now(), when);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (Exception parseFailure) {
                return fallback;
            }
        }
    }

    private static IllegalStateException apiException(HttpClientResponseException exception) {
        int code = statusCode(exception);
        String body = bodyText(exception.getResponse() == null ? null : exception.getResponse().getBody());
        String message = null;
        String errorCode = null;
        Map<String, Object> parsed = tryJsonMap(body);
        if (parsed != null) {
            errorCode = string(parsed.get("code"));
            message = string(parsed.get("message"));
        }
        if (code == 402 && message != null && message.contains("Insufficient balance")) {
            errorCode = "insufficient_balance";
        }
        String detail = message == null || message.isBlank() ? SoniloSupport.truncate(body) : SoniloSupport.truncate(message);
        String renderedCode = errorCode == null || errorCode.isBlank() ? Integer.toString(code) : code + " " + errorCode;
        return new IllegalStateException("Sonilo request failed (" + renderedCode + "): " + detail);
    }

    private static IllegalStateException failure(Map<String, Object> task) {
        String taskId = string(task.get("task_id"));
        String status = SoniloSupport.statusOf(task);
        String code = null;
        String message = null;
        Object error = task.get("error");
        if (error instanceof String text) {
            message = text;
        } else if (error instanceof Map<?, ?> raw) {
            code = string(raw.get("code"));
            message = string(raw.get("message"));
        }
        StringBuilder text = new StringBuilder("Sonilo task ");
        text.append(taskId == null ? "(unknown)" : taskId).append(' ').append(status.isBlank() ? "failed" : status);
        if (code != null) {
            text.append(" (").append(code).append(')');
        }
        if (message != null) {
            text.append(": ").append(SoniloSupport.truncate(message));
        }
        return new IllegalStateException(text.toString());
    }

    private static String streamError(Map<String, Object> event) {
        String code = string(event.get("code"));
        String message = string(event.get("message"));
        if (message == null || message.isBlank()) {
            message = "generation failed";
        }
        if (code == null) {
            return "Sonilo stream failed: " + message;
        }
        return "Sonilo stream failed (" + code + "): " + message;
    }

    private static int statusCode(HttpClientResponseException exception) {
        if (exception.getResponse() == null || exception.getResponse().getStatus() == null) {
            return 0;
        }
        return exception.getResponse().getStatus().getCode();
    }

    private static HttpClientResponseException responseException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpClientResponseException response) {
                return response;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String bodyText(Object body) {
        if (body == null) {
            return "";
        }
        if (body instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (body instanceof String text) {
            return text;
        }
        try {
            return JacksonMapper.ofJson().writeValueAsString(body);
        } catch (Exception exception) {
            return String.valueOf(body);
        }
    }

    private static boolean hasDownload(Map<String, Object> task) {
        return !mediaList(task.get("audio")).isEmpty() || string(task.get("output_url")) != null;
    }

    private static String titleOf(Map<String, Object> task, List<Media> audio) {
        String title = titleText(task.get("title"));
        if (title != null) {
            return title;
        }
        return audio.isEmpty() ? null : audio.getFirst().title();
    }

    private static String titleText(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        Map<String, Object> object = asMap(value);
        if (object == null) {
            return null;
        }
        return string(object.get("title"));
    }

    private static List<Media> mediaList(Object value) {
        if (value instanceof Map<?, ?> map) {
            Media media = media(asMap(map));
            return media == null ? List.of() : List.of(media);
        }
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Media> media = new ArrayList<>();
        for (Object item : list) {
            Media parsed = media(asMap(item));
            if (parsed != null) {
                media.add(parsed);
            }
        }
        media.sort(Comparator.comparing(item -> item.streamIndex() == null ? Integer.MAX_VALUE : item.streamIndex()));
        return media;
    }

    private static Media singleMedia(Object value) {
        List<Media> media = mediaList(value);
        return media.isEmpty() ? null : media.getFirst();
    }

    private static Media media(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        String url = firstText(value, "url", "audio_url", "output_url");
        if (url == null) {
            return null;
        }
        Object size = value.get("file_size");
        if (size instanceof Number number && number.longValue() == 0) {
            throw new IllegalStateException("Sonilo returned an empty audio file");
        }
        Integer streamIndex = value.get("stream_index") instanceof Number number ? number.intValue() : null;
        return new Media(url, string(value.get("content_type")), streamIndex, titleText(value.get("title")));
    }

    private static Map<String, Object> jsonMap(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Sonilo returned an empty response");
        }
        try {
            Object parsed = JacksonMapper.ofJson().readValue(body, Object.class);
            Map<String, Object> map = asMap(parsed);
            if (map == null) {
                throw new IllegalStateException("Sonilo returned a response that is not a JSON object");
            }
            return map;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Sonilo returned malformed JSON", exception);
        }
    }

    private static Map<String, Object> tryJsonMap(String body) {
        try {
            return jsonMap(body);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Map<String, Object> jsonObjectOrNull(String line) {
        try {
            Object parsed = JacksonMapper.ofJson().readValue(line, Object.class);
            return asMap(parsed);
        } catch (Exception exception) {
            throw new IllegalStateException("Sonilo returned a malformed response line", exception);
        }
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, item) -> map.put(String.valueOf(key), item));
        return map;
    }

    private static String readText(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String readText(BufferedReader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(line);
        }
        return text.toString();
    }

    private static String nextDataLine(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return null;
    }

    private static void closeStreams(Map<Integer, OutputStream> open) {
        for (OutputStream output : open.values()) {
            try {
                output.close();
            } catch (IOException ignored) {
            }
        }
        open.clear();
    }

    private static String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = string(map.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    record FileUpload(java.io.File file) {
    }

    private record ParsedBody(Map<String, Object> task, StreamCapture capture) {
        static ParsedBody task(Map<String, Object> task) {
            return new ParsedBody(task, null);
        }

        static ParsedBody stream(StreamCapture capture) {
            return new ParsedBody(null, capture);
        }
    }

    private record StreamCapture(String title, Map<Integer, Path> paths, long totalBytes, String fallbackUrl) {
    }

    private record Media(String url, String contentType, Integer streamIndex, String title) {
    }

    private record Downloaded(URI uri, long bytes, String contentType) {
    }

    private record StoredMedia(
        String taskId,
        URI audioUri,
        List<URI> audioUris,
        String title,
        String contentType,
        String outputType,
        URI duckedUri,
        Map<String, URI> stemUris,
        String stemsError,
        URI vocalsUri,
        List<URI> muxUris
    ) {
        AbstractSonilo.Output toOutput(String status) {
            return AbstractSonilo.Output.builder()
                .taskId(taskId)
                .status(status == null || status.isBlank() ? "succeeded" : status)
                .audioUri(audioUri)
                .audioUris(audioUris)
                .title(title)
                .contentType(contentType)
                .outputType(outputType)
                .duckedUri(duckedUri)
                .stemUris(stemUris)
                .stemsError(stemsError)
                .vocalsUri(vocalsUri)
                .muxUris(muxUris)
                .build();
        }
    }
}
