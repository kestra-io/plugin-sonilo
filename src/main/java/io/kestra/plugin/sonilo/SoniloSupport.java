package io.kestra.plugin.sonilo;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure Sonilo request and response helpers shared by tasks, the trigger, and tests.
 */
final class SoniloSupport {
    static final String DEFAULT_BASE_URL = "https://api.sonilo.com";

    private static final Set<String> SUCCESS = Set.of("succeeded", "completed", "success");
    private static final Set<String> FAILURE = Set.of("failed", "canceled", "cancelled", "error");

    private SoniloSupport() {
    }

    static String endpoint(String baseUrl, String path) {
        String base = normalizeBase(baseUrl);
        String suffix = path == null ? "" : path.trim();
        if (suffix.isEmpty()) {
            throw new IllegalArgumentException("Sonilo path is empty");
        }
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        if (base.endsWith("/v1")) {
            return base + suffix;
        }
        return base + "/v1" + suffix;
    }

    static String normalizeBase(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    static String taskPath(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        return "/tasks/" + URLEncoder.encode(taskId, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String selectMode(String requested, boolean needsAsync) {
        if (requested == null || requested.isBlank()) {
            return needsAsync ? "async" : "stream";
        }
        String mode = requested.trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("stream") && !mode.equals("async")) {
            throw new IllegalArgumentException("mode must be stream or async");
        }
        if ("stream".equals(mode) && needsAsync) {
            throw new IllegalArgumentException(
                "mode=stream cannot be combined with wav or mp3 output, variantsNum greater than 1, stems, ducking, or preserveSpeech"
            );
        }
        return mode;
    }

    static boolean needsAsync(String outputFormat, Integer variantsNum, Boolean stems, Boolean ducking, Boolean preserveSpeech) {
        boolean transcoded = outputFormat != null && (outputFormat.equalsIgnoreCase("wav") || outputFormat.equalsIgnoreCase("mp3"));
        boolean manyVariants = variantsNum != null && variantsNum > 1;
        return transcoded
            || manyVariants
            || Boolean.TRUE.equals(stems)
            || Boolean.TRUE.equals(ducking)
            || Boolean.TRUE.equals(preserveSpeech);
    }

    static String statusOf(Map<String, Object> body) {
        Object status = body == null ? null : body.get("status");
        if (status == null) {
            return "";
        }
        return status.toString().trim().toLowerCase(Locale.ROOT);
    }

    static boolean isSuccess(String status) {
        return SUCCESS.contains(status);
    }

    static boolean isFailure(String status) {
        return FAILURE.contains(status);
    }

    static boolean isTerminal(String status) {
        return isSuccess(status) || isFailure(status);
    }

    static String formatNumber(Number number) {
        if (number instanceof Integer || number instanceof Long || number instanceof Short || number instanceof Byte) {
            return number.toString();
        }
        double value = number.doubleValue();
        if (Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) < 1.0e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    static String extensionFor(String contentType, String formatHint, boolean streamMusic) {
        if (streamMusic) {
            return ".m4a";
        }
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        int separator = mime.indexOf(';');
        if (separator >= 0) {
            mime = mime.substring(0, separator).trim();
        }
        return switch (mime) {
            case "audio/mp4", "audio/m4a", "audio/x-m4a" -> ".m4a";
            case "audio/aac" -> ".aac";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> ".wav";
            case "audio/flac" -> ".flac";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            default -> {
                if (formatHint != null && !formatHint.isBlank()) {
                    String hint = formatHint.trim().toLowerCase(Locale.ROOT);
                    yield hint.startsWith(".") ? hint : "." + hint;
                }
                yield ".bin";
            }
        };
    }

    static String sanitizeFilename(String name, String fallback) {
        String candidate = name == null || name.isBlank() ? fallback : name;
        int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < candidate.length()) {
            candidate = candidate.substring(slash + 1);
        }
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]", "_");
        if (candidate.isBlank() || ".".equals(candidate) || "..".equals(candidate)) {
            candidate = fallback;
        }
        return candidate;
    }

    static String fileName(URI uri, String fallback) {
        if (uri == null || uri.getPath() == null || uri.getPath().isBlank()) {
            return fallback;
        }
        return sanitizeFilename(uri.getPath(), fallback);
    }

    static String kvKey(String flowId, String triggerId, String taskId) {
        String raw = "sonilo_" + nullToEmpty(flowId) + "_" + nullToEmpty(triggerId) + "_" + nullToEmpty(taskId);
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            boolean allowed = (current >= 'a' && current <= 'z')
                || (current >= 'A' && current <= 'Z')
                || (current >= '0' && current <= '9')
                || current == '.'
                || current == '_'
                || current == '-';
            key.append(allowed ? current : '_');
        }
        if (key.isEmpty() || !Character.isLetterOrDigit(key.charAt(0))) {
            key.insert(0, 'k');
        }
        return key.toString();
    }

    static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        if (trimmed.length() <= 1000) {
            return trimmed;
        }
        return trimmed.substring(0, 1000) + "...";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
