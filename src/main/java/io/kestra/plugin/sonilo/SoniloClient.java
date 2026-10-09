package io.kestra.plugin.sonilo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

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
        String rToken = runContext.render(connection.getApiToken()).as(String.class).orElse("").trim();
        if (rToken.isBlank()) {
            throw new IllegalArgumentException("apiToken is required");
        }
        this.token = rToken;
        String rBase = connection.getBaseUrl() == null
            ? SoniloSupport.DEFAULT_BASE_URL
            : runContext.render(connection.getBaseUrl()).as(String.class).orElse(SoniloSupport.DEFAULT_BASE_URL);
        this.baseUrl = SoniloSupport.normalizeBase(rBase);
        this.options = connection.getOptions();
    }

    Generation generate(String path, Map<String, Object> form, Duration pollInterval, Duration waitTimeout) throws Exception {
        state.checkKilled();
        runContext.logger().info("Submitting Sonilo request to {}", path);
        runContext.logger().debug("Sonilo form fields {}", form.keySet());
        ParsedBody body = post(path, form);
        if (body.task() != null && body.capture() == null) {
            return awaitIfNeeded(body.task(), pollInterval, waitTimeout);
        }
        return storeStream(body.capture());
    }

    SoniloResponse.TaskView fetchTask(String taskId) throws Exception {
        String path = SoniloSupport.taskPath(taskId);
        runContext.logger().debug("Checking Sonilo task {}", taskId);
        return getJson(path);
    }

    URI storePrimary(SoniloResponse.TaskView task) throws Exception {
        Generation stored = storeAll(task, true);
        if (stored.audioUri() == null) {
            throw new IllegalStateException("Sonilo task " + taskLabel(stored.taskId()) + " succeeded without a downloadable file");
        }
        return stored.audioUri();
    }

    String primaryUrl(SoniloResponse.TaskView task) {
        if (!task.audio().isEmpty()) {
            return task.audio().getFirst().url();
        }
        if (task.outputUrl() != null) {
            return task.outputUrl();
        }
        if (!task.ducked().isEmpty()) {
            return task.ducked().getFirst().url();
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

    private Generation awaitIfNeeded(SoniloResponse.TaskView task, Duration pollInterval, Duration waitTimeout) throws Exception {
        String status = task.status();
        String taskId = task.taskId();
        if (SoniloSupport.isFailure(status)) {
            throw failure(task);
        }
        if (taskId != null && !SoniloSupport.isTerminal(status)) {
            task = poll(taskId, pollInterval, waitTimeout);
            status = task.status();
        }
        if (SoniloSupport.isFailure(status)) {
            throw failure(task);
        }
        if (taskId == null && !task.hasDownload()) {
            throw new IllegalStateException("Sonilo response did not include audio or a task_id");
        }
        if (!SoniloSupport.isSuccess(status) && !task.hasDownload()) {
            throw new IllegalStateException("Sonilo task " + taskLabel(taskId) + " ended with status " + status);
        }
        String shown = SoniloSupport.isSuccess(status) ? status : "succeeded";
        return storeAll(task, false).withStatus(shown);
    }

    private SoniloResponse.TaskView poll(String taskId, Duration pollInterval, Duration waitTimeout) throws Exception {
        Instant deadline = Instant.now().plus(waitTimeout);
        while (true) {
            state.checkKilled();
            SoniloResponse.TaskView task = fetchTask(taskId);
            String status = task.status();
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

    private SoniloResponse.TaskView getJson(String path) throws Exception {
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
        return SoniloResponse.parseTask(raw[0]);
    }

    private HttpRequest.HttpRequestBuilder authorized(String method, String url) {
        return HttpRequest.builder()
            .method(method)
            .uri(URI.create(url))
            .addHeader("Authorization", "Bearer " + token);
    }

    /**
     * Kestra's client also retries 429 and 503 once. This loop honors Retry-After and retries 502.
     */
    private void exchange(HttpRequest request, Consumer<HttpResponse<InputStream>> consumer) throws Exception {
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
        state.client.set(httpClient);
        if (state.killed.get()) {
            httpClient.close();
            throw new CancellationException("Sonilo task was killed");
        }
        return httpClient;
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
                    return ParsedBody.task(SoniloResponse.parseTask(text));
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
        JsonNode object = SoniloResponse.parseLine(first);
        if (object != null && object.hasNonNull("type")) {
            return readNdjson(reader, first);
        }
        if (object != null && (object.hasNonNull("task_id") || object.hasNonNull("status") || object.hasNonNull("output_url"))) {
            String rest = readText(reader).strip();
            if (rest.isEmpty()) {
                return ParsedBody.task(SoniloResponse.task(object));
            }
            return readNdjson(new BufferedReader(new StringReader(first + "\n" + rest)));
        }
        String rest = readText(reader);
        String text = rest.isBlank() ? first : first + "\n" + rest;
        try {
            return ParsedBody.task(SoniloResponse.parseTask(text));
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
        SoniloResponse.TaskView[] ack = new SoniloResponse.TaskView[1];
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
        SoniloResponse.TaskView[] ack
    ) throws IOException {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        JsonNode node = SoniloResponse.parseLine(trimmed);
        if (node == null) {
            return;
        }
        SoniloResponse.StreamEvent event = SoniloResponse.event(node);
        if (event.type() == null) {
            // A present type, including "", is an event. Only an object with no type can be a task ack.
            if (!node.hasNonNull("type") && event.taskId() != null && total[0] == 0 && !complete[0]) {
                taskAck[0] = true;
                ack[0] = SoniloResponse.task(node);
            }
            return;
        }
        runContext.logger().debug("Sonilo stream event {}", event.type());
        switch (event.type()) {
            case "audio_chunk" -> writeChunk(event, open, paths, total);
            case "title" -> {
                if (event.title() != null) {
                    title[0] = event.title();
                }
            }
            case "complete" -> {
                complete[0] = true;
                if (fallback[0] == null) {
                    fallback[0] = event.fallbackUrl();
                }
            }
            case "error" -> throw new IllegalStateException(streamError(event));
            default -> {
            }
        }
    }

    private void writeChunk(SoniloResponse.StreamEvent event, Map<Integer, OutputStream> open, Map<Integer, Path> paths, long[] total) throws IOException {
        JsonNode data = event.data();
        if (data == null || !data.isTextual()) {
            throw new IllegalStateException("Sonilo stream returned a malformed audio_chunk event (missing or non-decodable data)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data.asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Sonilo stream returned a malformed audio_chunk event (missing or non-decodable data)", exception);
        }
        int index = event.streamIndex() == null ? 0 : event.streamIndex();
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

    private Generation storeStream(StreamCapture capture) throws Exception {
        if (capture.totalBytes() == 0) {
            if (capture.fallbackUrl() != null) {
                Downloaded downloaded = download(capture.fallbackUrl(), "sonilo-stream.m4a", null, true);
                runContext.logger().info("Stored Sonilo audio ({} bytes)", downloaded.bytes());
                return new Generation(
                    null,
                    "succeeded",
                    downloaded.uri(),
                    List.of(downloaded.uri()),
                    capture.title(),
                    downloaded.contentType(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
                );
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
        return new Generation(null, "succeeded", uris.getFirst(), uris, capture.title(), contentType, null, null, null, null, null, null);
    }

    private Generation storeAll(SoniloResponse.TaskView task, boolean primaryOnly) throws Exception {
        String taskId = task.taskId();
        String prefix = taskId == null ? "sonilo" : "sonilo-" + SoniloSupport.sanitizeFilename(taskId, "task");
        List<URI> audioUris = new ArrayList<>();
        URI audioUri = null;
        String contentType = null;
        URI duckedUri = null;
        if (task.audio().isEmpty() && task.outputUrl() != null) {
            Downloaded downloaded = download(
                task.outputUrl(),
                prefix + "-output" + SoniloSupport.extensionFor(null, "video".equals(task.outputType()) ? "mp4" : "wav", false),
                task.outputType(),
                false
            );
            audioUri = downloaded.uri();
            audioUris.add(downloaded.uri());
            contentType = downloaded.contentType();
            runContext.logger().info("Stored Sonilo audio ({} bytes)", downloaded.bytes());
        } else {
            Set<String> names = new HashSet<>();
            int ordinal = 0;
            for (SoniloResponse.MediaFile media : task.audio()) {
                Downloaded downloaded = download(media.url(), prefix + "-audio-" + index(media, ordinal++, names) + extension(media), null, false);
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
            Set<String> names = new HashSet<>();
            int ordinal = 0;
            for (SoniloResponse.MediaFile media : task.ducked()) {
                Downloaded downloaded = download(media.url(), prefix + "-ducked-" + index(media, ordinal++, names) + extension(media), null, false);
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
            stemUris = storeStems(task.stems(), prefix);
            if (task.vocals() != null) {
                vocalsUri = download(task.vocals().url(), prefix + "-vocals" + extension(task.vocals()), null, false).uri();
            }
            if (!task.mux().isEmpty()) {
                muxUris = new ArrayList<>();
                Set<String> names = new HashSet<>();
                int ordinal = 0;
                for (SoniloResponse.MediaFile media : task.mux()) {
                    muxUris.add(download(media.url(), prefix + "-mux-" + index(media, ordinal++, names) + extension(media), null, false).uri());
                }
            }
        }
        if (audioUri == null && !primaryOnly) {
            throw new IllegalStateException("Sonilo task " + taskLabel(taskId) + " succeeded without a downloadable file");
        }
        String title = task.title();
        if (title == null && !task.audio().isEmpty()) {
            title = task.audio().getFirst().title();
        }
        return new Generation(
            taskId,
            null,
            audioUri,
            audioUris.isEmpty() ? null : List.copyOf(audioUris),
            title,
            contentType,
            task.outputType(),
            duckedUri,
            stemUris,
            task.stemsError(),
            vocalsUri,
            muxUris
        );
    }

    private Map<String, URI> storeStems(List<SoniloResponse.StemGroup> stems, String prefix) throws Exception {
        if (stems == null || stems.isEmpty()) {
            return null;
        }
        Map<String, URI> stored = new LinkedHashMap<>();
        for (SoniloResponse.StemGroup group : stems) {
            for (var entry : group.files().entrySet()) {
                SoniloResponse.MediaFile media = entry.getValue();
                URI uri = download(
                    media.url(),
                    prefix + "-stem-" + group.streamIndex() + "-" + entry.getKey() + extension(media),
                    null,
                    false
                ).uri();
                stored.put(group.streamIndex() + "." + entry.getKey(), uri);
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

    private String extension(SoniloResponse.MediaFile media) {
        return SoniloSupport.extensionFor(media.contentType(), null, false);
    }

    /**
     * A missing stream index sorts after numbered tracks, so its file name must not reuse {@code 0}.
     * A repeated index gets an ordinal suffix. Otherwise the second putFile overwrites the first.
     */
    private static String index(SoniloResponse.MediaFile media, int ordinal, Set<String> used) {
        String base = media.streamIndex() == null ? "u" + ordinal : media.streamIndex().toString();
        if (used.add(base)) {
            return base;
        }
        String unique = base + "-" + ordinal;
        used.add(unique);
        return unique;
    }

    private static String taskLabel(String taskId) {
        return taskId == null || taskId.isBlank() ? "(unknown)" : taskId;
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
        SoniloResponse.ApiError parsed = SoniloResponse.tryApiError(body);
        String message = parsed == null ? null : parsed.message();
        String errorCode = parsed == null ? null : parsed.code();
        if (code == 402 && message != null && message.contains("Insufficient balance")) {
            errorCode = "insufficient_balance";
        }
        String detail = message == null || message.isBlank() ? SoniloSupport.truncate(body) : SoniloSupport.truncate(message);
        String codeLabel = errorCode == null || errorCode.isBlank() ? Integer.toString(code) : code + " " + errorCode;
        return new IllegalStateException("Sonilo request failed (" + codeLabel + "): " + detail);
    }

    private static IllegalStateException failure(SoniloResponse.TaskView task) {
        String taskId = task.taskId();
        String status = task.status();
        SoniloResponse.ErrorView error = task.error();
        String code = error == null ? null : error.code();
        String message = error == null ? null : error.message();
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

    private static String streamError(SoniloResponse.StreamEvent event) {
        String code = event.code();
        String message = event.message();
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

    record FileUpload(java.io.File file) {
    }

    private record ParsedBody(SoniloResponse.TaskView task, StreamCapture capture) {
        static ParsedBody task(SoniloResponse.TaskView task) {
            return new ParsedBody(task, null);
        }

        static ParsedBody stream(StreamCapture capture) {
            return new ParsedBody(null, capture);
        }
    }

    private record StreamCapture(String title, Map<Integer, Path> paths, long totalBytes, String fallbackUrl) {
    }

    private record Downloaded(URI uri, long bytes, String contentType) {
    }

    record Generation(
        String taskId,
        String status,
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
        Generation withStatus(String status) {
            String shown = status == null || status.isBlank() ? "succeeded" : status;
            return new Generation(
                taskId,
                shown,
                audioUri,
                audioUris,
                title,
                contentType,
                outputType,
                duckedUri,
                stemUris,
                stemsError,
                vocalsUri,
                muxUris
            );
        }
    }
}
