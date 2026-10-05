package io.kestra.plugin.sonilo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.serializers.JacksonMapper;

/**
 * Typed reads of Sonilo task JSON and NDJSON events.
 * Audio may be one object or an array, a title may be a string or an object, and an error may be either.
 */
final class SoniloResponse {
    private static final List<String> STEM_NAMES = List.of("drums", "bass", "vocals", "other");

    private SoniloResponse() {
    }

    static TaskView parseTask(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Sonilo returned an empty response");
        }
        try {
            JsonNode node = JacksonMapper.ofJson().readValue(body, JsonNode.class);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("Sonilo returned a response that is not a JSON object");
            }
            return task(node);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Sonilo returned malformed JSON", exception);
        }
    }

    static TaskView task(JsonNode node) {
        return new TaskView(
            scalar(node.get("task_id")),
            raw(node.get("status")),
            SoniloSupport.normalizeStatus(raw(node.get("status"))),
            scalar(node.get("output_url")),
            scalar(node.get("output_type")),
            titleText(node.get("title")),
            scalar(node.get("stems_error")),
            error(node.get("error")),
            mediaList(node.get("audio")),
            mediaList(node.get("ducked")),
            single(node.get("vocals")),
            mediaList(node.get("mux")),
            stems(node.get("stems"))
        );
    }

    /**
     * @return the object, or null when the line is JSON that is not an object
     */
    static JsonNode parseLine(String line) {
        try {
            JsonNode node = JacksonMapper.ofJson().readValue(line, JsonNode.class);
            return node != null && node.isObject() ? node : null;
        } catch (Exception exception) {
            throw new IllegalStateException("Sonilo returned a malformed response line", exception);
        }
    }

    static StreamEvent event(JsonNode node) {
        JsonNode data = node.get("data");
        Integer streamIndex = node.has("stream_index") && node.get("stream_index").isNumber()
            ? node.get("stream_index").intValue()
            : null;
        return new StreamEvent(
            scalar(node.get("type")),
            scalar(node.get("task_id")),
            data,
            streamIndex,
            titleText(node.get("title")),
            firstText(node, "url", "audio_url"),
            scalar(node.get("code")),
            scalar(node.get("message"))
        );
    }

    static ApiError tryApiError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JacksonMapper.ofJson().readValue(body, JsonNode.class);
            if (node == null || !node.isObject()) {
                return null;
            }
            return new ApiError(scalar(node.get("code")), scalar(node.get("message")));
        } catch (Exception exception) {
            return null;
        }
    }

    static List<MediaFile> mediaList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isObject()) {
            MediaFile media = media(node);
            return media == null ? List.of() : List.of(media);
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<MediaFile> media = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isObject()) {
                MediaFile parsed = media(item);
                if (parsed != null) {
                    media.add(parsed);
                }
            }
        }
        media.sort(Comparator.comparing(item -> item.streamIndex() == null ? Integer.MAX_VALUE : item.streamIndex()));
        return media;
    }

    static MediaFile single(JsonNode node) {
        List<MediaFile> media = mediaList(node);
        return media.isEmpty() ? null : media.getFirst();
    }

    private static List<StemGroup> stems(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<StemGroup> groups = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            int index = item.has("stream_index") && item.get("stream_index").isNumber()
                ? item.get("stream_index").intValue()
                : 0;
            Map<String, MediaFile> files = new LinkedHashMap<>();
            for (String name : STEM_NAMES) {
                MediaFile media = single(item.get(name));
                if (media != null) {
                    files.put(name, media);
                }
            }
            if (!files.isEmpty()) {
                groups.add(new StemGroup(index, Map.copyOf(files)));
            }
        }
        return groups;
    }

    private static MediaFile media(JsonNode node) {
        String url = firstText(node, "url", "audio_url", "output_url");
        if (url == null) {
            return null;
        }
        JsonNode size = node.get("file_size");
        if (size != null && size.isNumber() && size.longValue() == 0L) {
            throw new IllegalStateException("Sonilo returned an empty audio file");
        }
        Integer streamIndex = node.has("stream_index") && node.get("stream_index").isNumber()
            ? node.get("stream_index").intValue()
            : null;
        return new MediaFile(url, scalar(node.get("content_type")), streamIndex, titleText(node.get("title")));
    }

    private static ErrorView error(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return new ErrorView(null, scalar(node));
        }
        if (node.isObject()) {
            return new ErrorView(scalar(node.get("code")), scalar(node.get("message")));
        }
        return null;
    }

    private static String titleText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return scalar(node);
        }
        if (node.isObject()) {
            return scalar(node.get("title"));
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            String value = scalar(node.get(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String raw(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isObject() || node.isArray()) {
            return node.toString().trim();
        }
        String text = node.asText();
        return text == null ? "" : text.trim();
    }

    private static String scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isObject() || node.isArray()) {
            return null;
        }
        String text = node.asText();
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record TaskView(
        String taskId,
        String rawStatus,
        String status,
        String outputUrl,
        String outputType,
        String title,
        String stemsError,
        ErrorView error,
        List<MediaFile> audio,
        List<MediaFile> ducked,
        MediaFile vocals,
        List<MediaFile> mux,
        List<StemGroup> stems
    ) {
        boolean hasDownload() {
            return !audio.isEmpty() || outputUrl != null;
        }
    }

    record MediaFile(String url, String contentType, Integer streamIndex, String title) {
    }

    record StemGroup(int streamIndex, Map<String, MediaFile> files) {
    }

    record ErrorView(String code, String message) {
    }

    record StreamEvent(
        String type,
        String taskId,
        JsonNode data,
        Integer streamIndex,
        String title,
        String fallbackUrl,
        String code,
        String message
    ) {
    }

    record ApiError(String code, String message) {
    }
}
