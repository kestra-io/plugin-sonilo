package io.kestra.plugin.sonilo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SoniloSupportTest {
    @Test
    void endpointAvoidsADoubleV1Prefix() {
        assertEquals("https://api.sonilo.com/v1/text-to-music", SoniloSupport.endpoint("https://api.sonilo.com", "/text-to-music"));
        assertEquals("https://api.sonilo.com/v1/text-to-music", SoniloSupport.endpoint("https://api.sonilo.com/", "text-to-music"));
        assertEquals("https://api.sonilo.com/v1/text-to-music", SoniloSupport.endpoint("https://api.sonilo.com/v1", "/text-to-music"));
        assertEquals("https://api.sonilo.com/v1/text-to-music", SoniloSupport.endpoint("https://api.sonilo.com/v1/", "/text-to-music"));
        assertEquals("https://api.sonilo.com/v1/text-to-music", SoniloSupport.endpoint(null, "/text-to-music"));
    }

    @Test
    void taskPathEncodesSlashesAndSpaces() {
        assertEquals("/tasks/a%2Fb", SoniloSupport.taskPath("a/b"));
        assertEquals("/tasks/a%20b", SoniloSupport.taskPath("a b"));
        assertFalse(SoniloSupport.taskPath("a b").contains("+"));
        assertThrows(IllegalArgumentException.class, () -> SoniloSupport.taskPath(" "));
    }

    @Test
    void selectModeRejectsExplicitStreamWhenAsyncIsRequired() {
        assertEquals("stream", SoniloSupport.selectMode(null, false));
        assertEquals("async", SoniloSupport.selectMode(null, true));
        assertEquals("async", SoniloSupport.selectMode(" ASYNC ", false));
        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class, () -> SoniloSupport.selectMode("stream", true));
        assertTrue(conflict.getMessage().contains("mode=stream"));
        assertThrows(IllegalArgumentException.class, () -> SoniloSupport.selectMode("batch", false));
    }

    @Test
    void needsAsyncMatchesTranscodeVariantsAndFlags() {
        assertFalse(SoniloSupport.needsAsync(null, null, null, null, null));
        assertFalse(SoniloSupport.needsAsync("m4a", 1, false, false, false));
        assertTrue(SoniloSupport.needsAsync("wav", null, null, null, null));
        assertTrue(SoniloSupport.needsAsync("MP3", 1, false, false, false));
        assertTrue(SoniloSupport.needsAsync("m4a", 2, false, false, false));
        assertTrue(SoniloSupport.needsAsync("m4a", 1, true, false, false));
        assertTrue(SoniloSupport.needsAsync("m4a", 1, false, true, false));
        assertTrue(SoniloSupport.needsAsync("m4a", 1, false, false, true));
    }

    @Test
    void formatNumberDropsAFractionOnlyForWholeValues() {
        assertEquals("30", SoniloSupport.formatNumber(30));
        assertEquals("8", SoniloSupport.formatNumber(8.0d));
        assertEquals("0.5", SoniloSupport.formatNumber(0.5d));
        assertEquals("0", SoniloSupport.formatNumber(0.0d));
    }

    @Test
    void extensionForUsesMimeThenHintAndForcesStreamMusic() {
        assertEquals(".m4a", SoniloSupport.extensionFor("audio/wav", "mp3", true));
        assertEquals(".m4a", SoniloSupport.extensionFor("audio/mp4; charset=binary", null, false));
        assertEquals(".aac", SoniloSupport.extensionFor("audio/aac", null, false));
        assertEquals(".mp3", SoniloSupport.extensionFor("audio/mpeg", null, false));
        assertEquals(".wav", SoniloSupport.extensionFor("audio/x-wav", null, false));
        assertEquals(".flac", SoniloSupport.extensionFor("audio/flac", null, false));
        assertEquals(".mp4", SoniloSupport.extensionFor("video/mp4", null, false));
        assertEquals(".webm", SoniloSupport.extensionFor("video/webm", null, false));
        assertEquals(".mp3", SoniloSupport.extensionFor(null, "mp3", false));
        assertEquals(".bin", SoniloSupport.extensionFor(null, null, false));
    }

    @Test
    void kvKeyAndTruncateStayWithinStorageLimits() {
        String key = SoniloSupport.kvKey("company.media", "wait/for", "a b");
        assertTrue(key.startsWith("sonilo_"));
        assertTrue(key.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*"));
        assertFalse(key.contains(" ") || key.contains("/"));
        assertEquals("", SoniloSupport.truncate(null));
        assertEquals("abc", SoniloSupport.truncate(" abc "));
        assertEquals(1003, SoniloSupport.truncate("x".repeat(1001)).length());
        assertTrue(SoniloSupport.truncate("x".repeat(1001)).endsWith("..."));
    }

    @Test
    void terminalStatusesIncludeSdkAndOpenApiSpellings() {
        assertTrue(SoniloSupport.isSuccess("succeeded"));
        assertTrue(SoniloSupport.isSuccess("completed"));
        assertTrue(SoniloSupport.isSuccess("success"));
        assertTrue(SoniloSupport.isFailure("failed"));
        assertTrue(SoniloSupport.isFailure("canceled"));
        assertTrue(SoniloSupport.isFailure("cancelled"));
        assertTrue(SoniloSupport.isFailure("error"));
        assertFalse(SoniloSupport.isTerminal("processing"));
        assertEquals("succeeded", SoniloSupport.statusOf(java.util.Map.of("status", " Succeeded ")));
    }
}
