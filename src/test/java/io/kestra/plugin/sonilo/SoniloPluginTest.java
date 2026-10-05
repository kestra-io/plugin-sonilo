package io.kestra.plugin.sonilo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

@KestraTest
class SoniloPluginTest {
    @Inject
    private RunContextFactory runContextFactory;

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void streamsMusicWithBearerMultipartAndPlainNumbers() throws Exception {
        byte[] audio = "stream-audio".getBytes(StandardCharsets.UTF_8);
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music")).willReturn(ndjson(
            "{\"type\":\"title\",\"title\":\"Night Drive\"}",
            chunk(audio, null),
            "{\"type\":\"complete\"}"
        )));

        RunContext runContext = runContextFactory.of();
        var output = music()
            .prompt(Property.ofValue("Warm analog synths"))
            .duration(Property.ofValue(30))
            .segments(Property.ofValue("[{\"at\":0}]"))
            .build()
            .run(runContext);

        LoggedRequest request = postRequest("/v1/text-to-music");
        assertMultipart(request);
        String body = request.getBodyAsString();
        assertTrue(body.contains("name=\"prompt\""));
        assertTrue(body.contains("Warm analog synths"));
        assertTrue(body.contains("name=\"duration\""));
        assertTrue(body.contains("30"));
        assertFalse(body.contains("30.0"));
        assertTrue(body.contains("name=\"mode\""));
        assertTrue(body.contains("stream"));
        assertTrue(body.contains("[{\"at\":0}]"));
        assertEquals("succeeded", output.getStatus());
        assertNull(output.getTaskId());
        assertEquals("Night Drive", output.getTitle());
        assertEquals("audio/mp4", output.getContentType());
        assertArrayEquals(audio, read(runContext, output.getAudioUri()));
        assertTrue(output.getAudioUri().toString().endsWith(".m4a"));
    }

    @Test
    void omittedWavModeSwitchesToAsyncAndPolls() throws Exception {
        byte[] audio = "wav-audio".getBytes(StandardCharsets.UTF_8);
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music"))
            .inScenario("wav")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(json("{\"task_id\":\"wav-1\",\"status\":\"processing\"}"))
            .willSetStateTo("done"));
        server.stubFor(get(urlPathEqualTo("/v1/tasks/wav-1"))
            .willReturn(json("""
                {"task_id":"wav-1","status":"succeeded","audio":[{"url":"/media/wav-1.wav","content_type":"audio/wav","file_size":9,"stream_index":0}]}
                """)));
        media("/media/wav-1.wav", "audio/wav", audio);

        RunContext runContext = runContextFactory.of();
        var output = music()
            .prompt(Property.ofValue("Wide strings"))
            .outputFormat(Property.ofValue(AbstractSonilo.OutputFormat.wav))
            .build()
            .run(runContext);

        String body = postRequest("/v1/text-to-music").getBodyAsString();
        assertTrue(body.contains("name=\"mode\""));
        assertTrue(body.contains("async"));
        assertTrue(body.contains("name=\"output_format\""));
        assertTrue(body.contains("wav"));
        assertEquals("wav-1", output.getTaskId());
        assertEquals("succeeded", output.getStatus());
        assertArrayEquals(audio, read(runContext, output.getAudioUri()));
        assertNull(mediaRequest("/media/wav-1.wav").getHeader("Authorization"));
    }

    @Test
    void explicitStreamWithWavThrowsBeforeHttp() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> music()
            .prompt(Property.ofValue("Wide strings"))
            .mode(Property.ofValue(AbstractSonilo.Mode.stream))
            .outputFormat(Property.ofValue(AbstractSonilo.OutputFormat.wav))
            .build()
            .run(runContextFactory.of()));
        assertTrue(exception.getMessage().contains("mode=stream"));
        assertTrue(server.getAllServeEvents().isEmpty());
    }

    @Test
    void groupsChunksByStreamIndexAndAllowsAnEmptyChunk() throws Exception {
        byte[] primary = "AAAA".getBytes(StandardCharsets.UTF_8);
        byte[] second = "BBBB".getBytes(StandardCharsets.UTF_8);
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music")).willReturn(ndjson(
            chunk(new byte[0], 1),
            chunk(second, 1),
            chunk(primary, 0),
            "{\"type\":\"complete\"}"
        )));

        RunContext runContext = runContextFactory.of();
        var output = music().prompt(Property.ofValue("Two beds")).build().run(runContext);

        assertEquals(2, output.getAudioUris().size());
        assertEquals(output.getAudioUri(), output.getAudioUris().getFirst());
        assertArrayEquals(primary, read(runContext, output.getAudioUris().get(0)));
        assertArrayEquals(second, read(runContext, output.getAudioUris().get(1)));
    }

    @Test
    void skipsNonObjectsCostAndUnknownEvents() throws Exception {
        byte[] audio = "hello".getBytes(StandardCharsets.UTF_8);
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music")).willReturn(ndjson(
            "null",
            "1",
            "\"skip\"",
            "{\"type\":\"cost\",\"credits\":3}",
            "{\"type\":\"nope\"}",
            chunk("", 0),
            chunk(audio, null),
            "{\"type\":\"title\",\"title\":{\"title\":\"Kept\"}}",
            "{\"type\":\"complete\"}"
        )));

        RunContext runContext = runContextFactory.of();
        var output = music().prompt(Property.ofValue("Keep me")).build().run(runContext);
        assertEquals("Kept", output.getTitle());
        assertArrayEquals(audio, read(runContext, output.getAudioUri()));
    }

    @Test
    void rejectsTruncatedEmptyAndMalformedStreams() {
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music"))
            .inScenario("bad-stream")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(ndjson(chunk("x".getBytes(StandardCharsets.UTF_8), null)))
            .willSetStateTo("empty"));
        IllegalStateException truncated = assertThrows(IllegalStateException.class, () -> music().prompt(Property.ofValue("cut")).build().run(runContextFactory.of()));
        assertTrue(truncated.getMessage().contains("complete"));

        server.stubFor(post(urlPathEqualTo("/v1/text-to-music"))
            .inScenario("bad-stream")
            .whenScenarioStateIs("empty")
            .willReturn(ndjson("{\"type\":\"complete\"}"))
            .willSetStateTo("malformed"));
        IllegalStateException empty = assertThrows(IllegalStateException.class, () -> music().prompt(Property.ofValue("empty")).build().run(runContextFactory.of()));
        assertTrue(empty.getMessage().contains("without audio"));

        server.stubFor(post(urlPathEqualTo("/v1/text-to-music"))
            .inScenario("bad-stream")
            .whenScenarioStateIs("malformed")
            .willReturn(ndjson("{\"type\":\"audio_chunk\",\"data\":\"not-valid-base64!!!\"}", "{\"type\":\"complete\"}"))
            .willSetStateTo("invalid"));
        IllegalStateException malformed = assertThrows(IllegalStateException.class, () -> music().prompt(Property.ofValue("bad")).build().run(runContextFactory.of()));
        assertTrue(malformed.getMessage().contains("malformed audio_chunk"));

        server.stubFor(post(urlPathEqualTo("/v1/text-to-music"))
            .inScenario("bad-stream")
            .whenScenarioStateIs("invalid")
            .willReturn(ndjson("{")));
        IllegalStateException invalid = assertThrows(IllegalStateException.class, () -> music().prompt(Property.ofValue("json")).build().run(runContextFactory.of()));
        assertTrue(invalid.getMessage().contains("malformed response line"));
    }

    @Test
    void downloadsCompleteFallbackWhenTheStreamHasNoChunks() throws Exception {
        byte[] audio = "fallback".getBytes(StandardCharsets.UTF_8);
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music")).willReturn(ndjson("{\"type\":\"complete\",\"url\":\"/media/fallback.m4a\"}")));
        media("/media/fallback.m4a", "application/octet-stream", audio);

        RunContext runContext = runContextFactory.of();
        var output = music().prompt(Property.ofValue("Fallback")).build().run(runContext);
        assertArrayEquals(audio, read(runContext, output.getAudioUri()));
        assertTrue(output.getAudioUri().toString().endsWith(".m4a"));
        assertNull(mediaRequest("/media/fallback.m4a").getHeader("Authorization"));
    }

    @Test
    void jsonTaskAckIsPolledAndRelativeMediaIsResolved() throws Exception {
        byte[] audio = "acked".getBytes(StandardCharsets.UTF_8);
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music")).willReturn(json("{\"task_id\":\"ack-1\",\"status\":\"processing\"}")));
        server.stubFor(get(urlPathEqualTo("/v1/tasks/ack-1")).willReturn(json("""
            {"task_id":"ack-1","status":"completed","audio":[{"url":"/media/ack.m4a","content_type":"audio/mp4","file_size":5,"stream_index":0}]}
            """)));
        media("/media/ack.m4a", "audio/mp4", audio);

        RunContext runContext = runContextFactory.of();
        var output = music()
            .prompt(Property.ofValue("Ack"))
            .mode(Property.ofValue(AbstractSonilo.Mode.stream))
            .build()
            .run(runContext);
        assertEquals("ack-1", output.getTaskId());
        assertEquals("completed", output.getStatus());
        assertArrayEquals(audio, read(runContext, output.getAudioUri()));
        assertTrue(mediaRequest("/media/ack.m4a").getUrl().startsWith("/media/ack.m4a"));
    }

    @Test
    void waitTimeoutSurfacesTheTaskId() {
        server.stubFor(post(urlPathEqualTo("/v1/text-to-sfx")).willReturn(json("{\"task_id\":\"slow-1\",\"status\":\"processing\"}")));
        server.stubFor(get(urlPathEqualTo("/v1/tasks/slow-1")).willReturn(json("{\"task_id\":\"slow-1\",\"status\":\"processing\"}")));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> sfx()
            .prompt(Property.ofValue("Long rain"))
            .pollInterval(Property.ofValue(Duration.ofMillis(20)))
            .waitTimeout(Property.ofValue(Duration.ofMillis(300)))
            .build()
            .run(runContextFactory.of()));
        assertTrue(exception.getMessage().contains("slow-1"));
        assertTrue(exception.getMessage().contains("did not finish within"));
    }

    @Test
    void pollsSfxWhenAudioIsAnObjectOrAnArray() throws Exception {
        byte[] objectAudio = "sfx-object".getBytes(StandardCharsets.UTF_8);
        server.stubFor(post(urlPathEqualTo("/v1/text-to-sfx")).willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody("{\"task_id\":\"sfx-1\",\"status\":\"queued\"}")));
        server.stubFor(get(urlPathEqualTo("/v1/tasks/sfx-1"))
            .inScenario("sfx")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(json("{\"task_id\":\"sfx-1\",\"status\":\"running\"}"))
            .willSetStateTo("done"));
        server.stubFor(get(urlPathEqualTo("/v1/tasks/sfx-1"))
            .inScenario("sfx")
            .whenScenarioStateIs("done")
            .willReturn(json("""
                {"task_id":"sfx-1","status":"succeeded","audio":{"url":"/media/sfx.mp3","content_type":"audio/mpeg","file_size":10}}
                """)));
        media("/media/sfx.mp3", "audio/mpeg", objectAudio);

        RunContext runContext = runContextFactory.of();
        var objectOutput = sfx().prompt(Property.ofValue("Footsteps")).duration(Property.ofValue(8.0d)).audioFormat(Property.ofValue("mp3")).build().run(runContext);
        String body = postRequest("/v1/text-to-sfx").getBodyAsString();
        assertTrue(body.contains("name=\"audio_format\""));
        assertTrue(body.contains("mp3"));
        assertTrue(body.contains("name=\"duration\""));
        assertFalse(body.contains("8.0"));
        assertArrayEquals(objectAudio, read(runContext, objectOutput.getAudioUri()));
        assertTrue(objectOutput.getAudioUri().toString().endsWith(".mp3"));

        server.resetAll();
        byte[] arrayAudio = "sfx-array".getBytes(StandardCharsets.UTF_8);
        server.stubFor(post(urlPathEqualTo("/v1/text-to-sfx")).willReturn(json("""
            {"task_id":"sfx-2","status":"success","audio":[{"url":"/media/sfx2.mp3","content_type":"audio/mp3","file_size":9,"stream_index":0}]}
            """)));
        media("/media/sfx2.mp3", "audio/mpeg", arrayAudio);
        var arrayOutput = sfx().prompt(Property.ofValue("Door")).build().run(runContext);
        assertArrayEquals(arrayAudio, read(runContext, arrayOutput.getAudioUri()));
    }

    @Test
    void keepsDistinctAudioWhenAStreamIndexIsMissing() throws Exception {
        server.stubFor(post(urlPathEqualTo("/v1/text-to-sfx")).willReturn(json("""
            {"task_id":"sfx-many","status":"succeeded","audio":[
              {"url":"/media/late.mp3","content_type":"audio/mpeg","file_size":1},
              {"url":"/media/second.mp3","content_type":"audio/mpeg","file_size":1,"stream_index":1},
              {"url":"/media/first.mp3","content_type":"audio/mpeg","file_size":1,"stream_index":0}
            ]}
            """)));
        media("/media/first.mp3", "audio/mpeg", new byte[] {'A'});
        media("/media/second.mp3", "audio/mpeg", new byte[] {'B'});
        media("/media/late.mp3", "audio/mpeg", new byte[] {'C'});

        RunContext runContext = runContextFactory.of();
        var output = sfx().prompt(Property.ofValue("Layers")).build().run(runContext);

        assertEquals(3, output.getAudioUris().size());
        assertEquals(output.getAudioUri(), output.getAudioUris().getFirst());
        assertArrayEquals(new byte[] {'A'}, read(runContext, output.getAudioUris().get(0)));
        assertArrayEquals(new byte[] {'B'}, read(runContext, output.getAudioUris().get(1)));
        assertArrayEquals(new byte[] {'C'}, read(runContext, output.getAudioUris().get(2)));
        assertNotEquals(output.getAudioUris().get(0), output.getAudioUris().get(2));
    }

    @Test
    void uploadsSfxVideoUsingItsStorageFilename() throws Exception {
        byte[] video = "clip-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] audio = "sfx-video".getBytes(StandardCharsets.UTF_8);
        RunContext runContext = runContextFactory.of();
        URI stored = runContext.storage().putFile(runContext.workingDir().createFile("clip.mp4", video).toFile());
        server.stubFor(post(urlPathEqualTo("/v1/video-to-sfx")).willReturn(json("""
            {"task_id":"sfx-video-1","status":"succeeded","audio":{"url":"/media/sfx-video.mp3","content_type":"audio/mpeg","file_size":9}}
            """)));
        media("/media/sfx-video.mp3", "audio/mpeg", audio);

        var output = sfxVideo().video(Property.ofValue(stored.toString())).build().run(runContext);
        LoggedRequest request = postRequest("/v1/video-to-sfx");
        assertMultipart(request);
        String body = request.getBodyAsString();
        assertTrue(body.contains("filename=\"clip.mp4\""), body);
        assertTrue(body.contains("clip-bytes"));
        assertFalse(body.contains("filename=\"video.mp4\""));
        assertArrayEquals(audio, read(runContext, output.getAudioUri()));
        assertTrue(output.getAudioUri().toString().endsWith(".mp3"));
    }

    @Test
    void sendsSfxVideoUrl() throws Exception {
        byte[] audio = "url-sfx".getBytes(StandardCharsets.UTF_8);
        server.stubFor(post(urlPathEqualTo("/v1/video-to-sfx")).willReturn(json("{\"task_id\":\"sfx-url-1\",\"status\":\"queued\"}")));
        server.stubFor(get(urlPathEqualTo("/v1/tasks/sfx-url-1")).willReturn(json("""
            {"task_id":"sfx-url-1","status":"succeeded","audio":[{"url":"/media/sfx-url.m4a","content_type":"audio/mp4","file_size":7,"stream_index":0}]}
            """)));
        media("/media/sfx-url.m4a", "audio/mp4", audio);

        RunContext runContext = runContextFactory.of();
        var output = sfxVideo()
            .videoUrl(Property.ofValue("https://cdn.example/scene.mp4"))
            .prompt(Property.ofValue("Soft room tone"))
            .audioFormat(Property.ofValue("m4a"))
            .build()
            .run(runContext);

        String body = postRequest("/v1/video-to-sfx").getBodyAsString();
        assertTrue(body.contains("name=\"video_url\""));
        assertTrue(body.contains("https://cdn.example/scene.mp4"));
        assertTrue(body.contains("name=\"prompt\""));
        assertTrue(body.contains("Soft room tone"));
        assertTrue(body.contains("name=\"audio_format\""));
        assertTrue(body.contains("m4a"));
        assertFalse(body.contains("filename="));
        assertArrayEquals(audio, read(runContext, output.getAudioUri()));
        assertEquals("sfx-url-1", output.getTaskId());
    }

    @Test
    void uploadsAVideoUsingItsStorageFilename() throws Exception {
        byte[] video = "clip-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] audio = "scored".getBytes(StandardCharsets.UTF_8);
        RunContext runContext = runContextFactory.of();
        URI stored = runContext.storage().putFile(runContext.workingDir().createFile("clip.mp4", video).toFile());
        server.stubFor(post(urlPathEqualTo("/v1/video-to-music")).willReturn(ndjson(chunk(audio, null), "{\"type\":\"complete\"}")));

        var output = videoMusic().video(Property.ofValue(stored.toString())).build().run(runContext);
        LoggedRequest request = postRequest("/v1/video-to-music");
        assertMultipart(request);
        String body = request.getBodyAsString();
        assertTrue(body.contains("filename=\"clip.mp4\""), body);
        assertTrue(body.contains("clip-bytes"));
        assertFalse(body.contains("filename=\"video.mp4\""));
        assertArrayEquals(audio, read(runContext, output.getAudioUri()));
    }

    @Test
    void sendsVideoUrlZeroInfluenceAndAnExplicitFalse() throws Exception {
        server.stubFor(post(urlPathEqualTo("/v1/video-to-music")).willReturn(ndjson(chunk("ok".getBytes(StandardCharsets.UTF_8), null), "{\"type\":\"complete\"}")));
        videoMusic()
            .videoUrl(Property.ofValue("https://cdn.example/clip.mp4"))
            .promptInfluence(Property.ofValue(0.0d))
            .preserveSpeech(Property.ofValue(false))
            .build()
            .run(runContextFactory.of());

        String body = postRequest("/v1/video-to-music").getBodyAsString();
        assertTrue(body.contains("name=\"video_url\""));
        assertTrue(body.contains("https://cdn.example/clip.mp4"));
        assertTrue(body.contains("name=\"prompt_influence\""));
        assertTrue(body.contains("\r\n0\r\n") || body.contains("\n0\n"));
        assertTrue(body.contains("name=\"preserve_speech\""));
        assertTrue(body.contains("false"));
        assertTrue(body.contains("stream"));
    }

    @Test
    void storesStemsVocalsMuxAndDuckedAudioByStreamIndex() throws Exception {
        server.stubFor(post(urlPathEqualTo("/v1/video-to-music")).willReturn(json("{\"task_id\":\"rich-1\",\"status\":\"processing\"}")));
        server.stubFor(get(urlPathEqualTo("/v1/tasks/rich-1")).willReturn(json("""
            {
              "task_id":"rich-1",
              "status":"succeeded",
              "title":{"title":"Variant Zero","summary":"first"},
              "output_type":"audio",
              "audio":[
                {"url":"/media/clean-1.m4a","content_type":"audio/mp4","file_size":1,"stream_index":1},
                {"url":"/media/clean-0.m4a","content_type":"audio/mp4","file_size":1,"stream_index":0,"title":{"title":"Entry title"}}
              ],
              "ducked":[{"url":"/media/ducked.m4a","content_type":"audio/mp4","file_size":1,"stream_index":0}],
              "vocals":{"url":"/media/vocals.wav","content_type":"audio/wav","file_size":1},
              "mux":[{"url":"/media/mux.m4a","content_type":"audio/mp4","file_size":1,"stream_index":0}],
              "stems":[{"stream_index":1,"drums":{"url":"/media/drums.wav","content_type":"audio/wav","file_size":1},"bass":{"url":"/media/bass.wav","content_type":"audio/wav","file_size":1}}],
              "stems_error":"other stem skipped"
            }
            """)));
        media("/media/clean-0.m4a", "audio/mp4", new byte[] {'A'});
        media("/media/clean-1.m4a", "audio/mp4", new byte[] {'B'});
        media("/media/ducked.m4a", "audio/mp4", new byte[] {'D'});
        media("/media/vocals.wav", "audio/wav", new byte[] {'V'});
        media("/media/mux.m4a", "audio/mp4", new byte[] {'M'});
        media("/media/drums.wav", "audio/wav", new byte[] {'R'});
        media("/media/bass.wav", "audio/wav", new byte[] {'S'});

        RunContext runContext = runContextFactory.of();
        var output = videoMusic()
            .videoUrl(Property.ofValue("https://cdn.example/scene.mp4"))
            .stems(Property.ofValue(true))
            .preserveSpeech(Property.ofValue(true))
            .ducking(Property.ofValue(true))
            .build()
            .run(runContext);

        String body = postRequest("/v1/video-to-music").getBodyAsString();
        assertTrue(body.contains("async"));
        assertTrue(body.contains("name=\"preserve_speech\""));
        assertTrue(body.contains("true"));
        assertTrue(body.contains("name=\"ducking\""));
        assertTrue(body.contains("name=\"stems\""));
        assertFalse(body.contains("ducking_ratio"));
        assertFalse(body.contains("isolate_vocals"));
        assertEquals("Variant Zero", output.getTitle());
        assertEquals(3, output.getAudioUris().size());
        assertArrayEquals(new byte[] {'A'}, read(runContext, output.getAudioUri()));
        assertArrayEquals(new byte[] {'D'}, read(runContext, output.getDuckedUri()));
        assertArrayEquals(new byte[] {'V'}, read(runContext, output.getVocalsUri()));
        assertArrayEquals(new byte[] {'M'}, read(runContext, output.getMuxUris().getFirst()));
        assertArrayEquals(new byte[] {'R'}, read(runContext, output.getStemUris().get("1.drums")));
        assertArrayEquals(new byte[] {'S'}, read(runContext, output.getStemUris().get("1.bass")));
        assertEquals("other stem skipped", output.getStemsError());
        assertNull(mediaRequest("/media/ducked.m4a").getHeader("Authorization"));
    }

    @Test
    void ducksAudioAndDownloadsTheMixWithoutAuthorization() throws Exception {
        byte[] mix = "ducked-mix".getBytes(StandardCharsets.UTF_8);
        server.stubFor(post(urlPathEqualTo("/v1/audio-ducking")).willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody("{\"task_id\":\"duck-1\",\"status\":\"processing\"}")));
        server.stubFor(get(urlPathEqualTo("/v1/tasks/duck-1")).willReturn(json("""
            {"task_id":"duck-1","status":"succeeded","output_url":"%s/media/mix.mp3","output_type":"audio","output_bytes":11}
            """.formatted(server.baseUrl()))));
        media("/media/mix.mp3", "audio/mpeg", mix);

        RunContext runContext = runContextFactory.of();
        var output = DuckAudio.builder()
            .id("duck_audio")
            .type(DuckAudio.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .pollInterval(Property.ofValue(Duration.ofMillis(20)))
            .waitTimeout(Property.ofValue(Duration.ofSeconds(3)))
            .voiceUrl(Property.ofValue("https://cdn.example/voice.wav"))
            .musicUrl(Property.ofValue("https://cdn.example/music.wav"))
            .build()
            .run(runContext);

        String body = postRequest("/v1/audio-ducking").getBodyAsString();
        assertTrue(body.contains("name=\"voice_url\""));
        assertTrue(body.contains("name=\"music_url\""));
        assertFalse(body.contains("ducking_ratio") || body.contains("attack_ms") || body.contains("release_ms"));
        assertEquals("audio", output.getOutputType());
        assertArrayEquals(mix, read(runContext, output.getAudioUri()));
        assertNull(mediaRequest("/media/mix.mp3").getHeader("Authorization"));
    }

    @Test
    void paymentRequiredAndUnavailableAreNotRetried() {
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music")).willReturn(aResponse().withStatus(402).withHeader("Content-Type", "application/json").withBody("{\"code\":\"payment_required\",\"message\":\"Insufficient balance for this request\"}")));
        IllegalStateException balance = assertThrows(IllegalStateException.class, () -> music().prompt(Property.ofValue("Paid")).build().run(runContextFactory.of()));
        assertTrue(balance.getMessage().contains("insufficient_balance"));
        assertTrue(balance.getMessage().contains("Insufficient balance"));
        assertEquals(1, requestsTo("/v1/text-to-music"));

        server.resetAll();
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music")).willReturn(aResponse().withStatus(503).withHeader("Content-Type", "application/json").withBody("{\"code\":\"not_configured\",\"message\":\"optional capability is not configured\"}")));
        IllegalStateException unavailable = assertThrows(IllegalStateException.class, () -> music().prompt(Property.ofValue("Missing")).build().run(runContextFactory.of()));
        assertTrue(unavailable.getMessage().contains("503"));
        assertTrue(unavailable.getMessage().contains("not_configured"));
        // Kestra's HttpClient retries 503 once before this plugin's loop sees the response. 402 is not retried.
        assertEquals(2, requestsTo("/v1/text-to-music"));
    }

    @Test
    void retriesRateLimitThenSucceedsAndDoesNotRetryUnauthorized() throws Exception {
        byte[] audio = "after-limit".getBytes(StandardCharsets.UTF_8);
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music"))
            .inScenario("limit")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0").withBody("{\"message\":\"slow down\"}"))
            .willSetStateTo("again"));
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music"))
            .inScenario("limit")
            .whenScenarioStateIs("again")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0").withBody("{\"message\":\"slow down\"}"))
            .willSetStateTo("ok"));
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music"))
            .inScenario("limit")
            .whenScenarioStateIs("ok")
            .willReturn(ndjson(chunk(audio, null), "{\"type\":\"complete\"}")));

        RunContext runContext = runContextFactory.of();
        var output = music().prompt(Property.ofValue("Retry")).build().run(runContext);
        assertArrayEquals(audio, read(runContext, output.getAudioUri()));
        // The HTTP client retries 429 once, then this plugin retries with Retry-After.
        assertEquals(3, requestsTo("/v1/text-to-music"));

        server.resetAll();
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music")).willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json").withBody("{\"code\":\"unauthorized\",\"message\":\"bad token\"}")));
        IllegalStateException unauthorized = assertThrows(IllegalStateException.class, () -> music().prompt(Property.ofValue("Nope")).build().run(runContextFactory.of()));
        assertTrue(unauthorized.getMessage().contains("401"));
        assertEquals(1, requestsTo("/v1/text-to-music"));
    }

    @Test
    void failedTaskIncludesIdCodeAndMessage() {
        server.stubFor(post(urlPathEqualTo("/v1/text-to-sfx")).willReturn(json("{\"task_id\":\"t-fail\",\"status\":\"processing\"}")));
        server.stubFor(get(urlPathEqualTo("/v1/tasks/t-fail")).willReturn(json("{\"task_id\":\"t-fail\",\"status\":\"failed\",\"error\":{\"code\":\"generation_failed\",\"message\":\"model exploded\"}}")));
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> sfx().prompt(Property.ofValue("Break")).build().run(runContextFactory.of()));
        assertTrue(exception.getMessage().contains("t-fail"));
        assertTrue(exception.getMessage().contains("generation_failed"));
        assertTrue(exception.getMessage().contains("model exploded"));
        assertEquals(0, requestsTo("/media/"));
    }

    @Test
    void rejectsInvalidInputsBeforeHttp() {
        RunContext runContext = runContextFactory.of();
        assertMessage("prompt is required", () -> music().prompt(Property.ofValue("  ")).build().run(runContext));
        assertMessage("apiToken is required", () -> music().prompt(Property.ofValue("x")).apiToken(Property.ofValue(" ")).build().run(runContext));
        assertMessage("duration must be between 5 and 360", () -> music().prompt(Property.ofValue("x")).duration(Property.ofValue(4)).build().run(runContext));
        assertMessage("variantsNum must be between 1 and 10", () -> music().prompt(Property.ofValue("x")).variantsNum(Property.ofValue(11)).build().run(runContext));
        assertMessage("pollInterval must be positive", () -> music().prompt(Property.ofValue("x")).pollInterval(Property.ofValue(Duration.ZERO)).build().run(runContext));
        assertMessage("Provide exactly one of video or videoUrl", () -> videoMusic().build().run(runContext));
        assertMessage("Provide exactly one of video or videoUrl", () -> videoMusic().video(Property.ofValue("kestra:///clip.mp4")).videoUrl(Property.ofValue("https://cdn.example/clip.mp4")).build().run(runContext));
        assertMessage("duration must be between 0.5 and 180", () -> sfx().prompt(Property.ofValue("x")).duration(Property.ofValue(0.4d)).build().run(runContext));
        assertMessage("promptInfluence must be between 0 and 1", () -> videoMusic().videoUrl(Property.ofValue("https://cdn.example/clip.mp4")).promptInfluence(Property.ofValue(1.1d)).build().run(runContext));
        assertMessage("Provide exactly one of voice or voiceUrl", () -> DuckAudio.builder()
            .id("duck_audio")
            .type(DuckAudio.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .musicUrl(Property.ofValue("https://cdn.example/music.wav"))
            .build()
            .run(runContext));
        assertTrue(server.getAllServeEvents().isEmpty());
    }

    @Test
    void baseUrlWithAndWithoutV1UsesASinglePrefix() throws Exception {
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music")).willReturn(ndjson(chunk("a".getBytes(StandardCharsets.UTF_8), null), "{\"type\":\"complete\"}")));
        music().prompt(Property.ofValue("Slash")).baseUrl(Property.ofValue(server.baseUrl() + "/")).build().run(runContextFactory.of());
        assertEquals("/v1/text-to-music", postRequest("/v1/text-to-music").getUrl());

        server.resetAll();
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music")).willReturn(ndjson(chunk("b".getBytes(StandardCharsets.UTF_8), null), "{\"type\":\"complete\"}")));
        music().prompt(Property.ofValue("Prefixed")).baseUrl(Property.ofValue(server.baseUrl() + "/v1")).build().run(runContextFactory.of());
        assertEquals("/v1/text-to-music", postRequest("/v1/text-to-music").getUrl());
        assertEquals(0, requestsTo("/v1/v1/"));
    }

    @Test
    void triggerEncodesTaskIdsAndFiresOnceForSuccessAndFailure() throws Exception {
        server.stubFor(get(urlPathMatching("/v1/tasks/.*")).willReturn(json("{\"status\":\"processing\"}")));
        assertTrue(evaluate(trigger("a/b")).isEmpty());
        assertTrue(evaluate(trigger("a b")).isEmpty());
        List<String> urls = server.getAllServeEvents().stream()
            .map(ServeEvent::getRequest)
            .map(LoggedRequest::getUrl)
            .filter(url -> url.contains("/tasks/"))
            .toList();
        String slash = urls.stream()
            .filter(url -> url.contains("%2F") || url.contains("/tasks/a/b"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(urls.toString()));
        String space = urls.stream()
            .filter(url -> url.contains("%20") || url.contains("+") || url.contains("a b"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(urls.toString()));
        assertTrue(slash.contains("a%2Fb"), urls.toString());
        assertFalse(slash.contains("/tasks/a/b"), urls.toString());
        assertTrue(space.contains("a%20b"), urls.toString());
        assertFalse(space.contains("+"), urls.toString());

        server.resetAll();
        // Namespace KV survives in local storage across JVMs. A fixed task id would already be marked fired.
        String successId = "task-" + UUID.randomUUID();
        String failureId = "bad-" + UUID.randomUUID();
        String mediaPath = "/media/" + successId + ".mp3";
        byte[] audio = "done".getBytes(StandardCharsets.UTF_8);
        server.stubFor(get(urlPathEqualTo("/v1/tasks/" + successId))
            .inScenario("watch")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(json("{\"task_id\":\"" + successId + "\",\"status\":\"processing\"}"))
            .willSetStateTo("done"));
        server.stubFor(get(urlPathEqualTo("/v1/tasks/" + successId))
            .inScenario("watch")
            .whenScenarioStateIs("done")
            .willReturn(json("{\"task_id\":\"" + successId + "\",\"status\":\"succeeded\",\"audio\":{\"url\":\"" + mediaPath + "\",\"content_type\":\"audio/mpeg\",\"file_size\":4}}")));
        media(mediaPath, "audio/mpeg", audio);

        Trigger success = trigger(successId);
        RunContext runContext = runContextFactory.of(flow(), success);
        assertTrue(evaluate(success, runContext).isEmpty());
        Optional<Execution> fired = evaluate(success, runContext);
        assertTrue(fired.isPresent());
        Map<String, Object> variables = fired.get().getTrigger().getVariables();
        assertEquals(successId, variables.get("taskId"));
        assertEquals("succeeded", variables.get("status"));
        assertNotNull(variables.get("audioUri"));
        assertArrayEquals(audio, read(runContext, URI.create(variables.get("audioUri").toString())));
        assertTrue(evaluate(success, runContext).isEmpty());
        assertEquals(1, requestsTo(mediaPath));
        assertNull(mediaRequest(mediaPath).getHeader("Authorization"));

        server.stubFor(get(urlPathEqualTo("/v1/tasks/" + failureId)).willReturn(json("{\"task_id\":\"" + failureId + "\",\"status\":\"failed\",\"error\":{\"code\":\"generation_failed\",\"message\":\"model exploded\"}}")));
        Trigger failure = trigger(failureId);
        RunContext failureContext = runContextFactory.of(flow(), failure);
        Optional<Execution> failed = evaluate(failure, failureContext);
        assertTrue(failed.isPresent());
        Map<String, Object> failedVariables = failed.get().getTrigger().getVariables();
        assertEquals("failed", failedVariables.get("status"));
        assertEquals("generation_failed", failedVariables.get("errorCode"));
        assertEquals("model exploded", failedVariables.get("error"));
        assertNull(failedVariables.get("audioUri"));
        assertTrue(evaluate(failure, failureContext).isEmpty());
        assertEquals(1, requestsTo(mediaPath));
    }

    @Test
    void triggerThrowsAuthAndNotFoundWithoutRetry() {
        server.stubFor(get(urlPathEqualTo("/v1/tasks/missing")).willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json").withBody("{\"message\":\"missing\"}")));
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> evaluate(trigger("missing")));
        assertTrue(missing.getMessage().contains("404"));
        assertEquals(1, requestsTo("/v1/tasks/missing"));

        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/v1/tasks/secret")).willReturn(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody("{\"code\":\"forbidden\",\"message\":\"no\"}")));
        IllegalStateException forbidden = assertThrows(IllegalStateException.class, () -> evaluate(trigger("secret")));
        assertTrue(forbidden.getMessage().contains("403"));
        assertEquals(1, requestsTo("/v1/tasks/secret"));
    }

    @Test
    @Timeout(value = 30)
    void overlappingTriggerEvaluationsFireOnce() throws Exception {
        String taskId = "race-" + UUID.randomUUID();
        String taskPath = "/v1/tasks/" + taskId;
        String mediaPath = "/media/" + taskId + ".mp3";
        byte[] audio = "race".getBytes(StandardCharsets.UTF_8);
        // Hold each pair of requests until both have arrived, so both evaluations pass alreadyFired
        // before either one stores the terminal status.
        CountDownLatch statusPair = new CountDownLatch(2);
        CountDownLatch mediaPair = new CountDownLatch(2);
        server.addMockServiceRequestListener((request, response) -> {
            String url = request.getUrl();
            CountDownLatch pair = null;
            if (url.contains(taskPath)) {
                pair = statusPair;
            } else if (url.contains(mediaPath)) {
                pair = mediaPair;
            }
            if (pair == null) {
                return;
            }
            pair.countDown();
            try {
                if (!pair.await(8, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for overlapping " + url);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for overlapping " + url, exception);
            }
        });
        server.stubFor(get(urlPathEqualTo(taskPath)).willReturn(json(
            "{\"task_id\":\"" + taskId + "\",\"status\":\"succeeded\",\"audio\":{\"url\":\"" + mediaPath + "\",\"content_type\":\"audio/mpeg\",\"file_size\":4}}"
        )));
        media(mediaPath, "audio/mpeg", audio);

        Trigger trigger = trigger(taskId);
        RunContext first = runContextFactory.of(flow(), trigger);
        RunContext second = runContextFactory.of(flow(), trigger);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<Execution>> firstResult = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return evaluate(trigger, first);
            });
            Future<Optional<Execution>> secondResult = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return evaluate(trigger, second);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Optional<Execution> left = firstResult.get(20, TimeUnit.SECONDS);
            Optional<Execution> right = secondResult.get(20, TimeUnit.SECONDS);
            long fired = (left.isPresent() ? 1 : 0) + (right.isPresent() ? 1 : 0);
            assertEquals(1, fired);
            Optional<Execution> winner = left.isPresent() ? left : right;
            assertEquals(taskId, winner.get().getTrigger().getVariables().get("taskId"));
            assertNotNull(winner.get().getTrigger().getVariables().get("audioUri"));
            // Both evaluations downloaded before either claim, so the single fire is the lock, not the clock.
            assertEquals(2, requestsTo(mediaPath));
            assertTrue(evaluate(trigger, first).isEmpty());
            assertEquals(2, requestsTo(mediaPath));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 20)
    void killUnblocksADelayedResponse() throws Exception {
        server.stubFor(post(urlPathEqualTo("/v1/text-to-music")).willReturn(aResponse()
            .withFixedDelay(12_000)
            .withStatus(200)
            .withHeader("Content-Type", "application/x-ndjson")
            .withBody(String.join("\n", chunk("late".getBytes(StandardCharsets.UTF_8), null), "{\"type\":\"complete\"}"))));
        CountDownLatch started = new CountDownLatch(1);
        server.addMockServiceRequestListener((request, response) -> started.countDown());

        GenerateMusicFromText task = music().prompt(Property.ofValue("Kill me")).build();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<GenerateMusicFromText.Output> future = executor.submit(() -> task.run(runContextFactory.of()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            task.kill();
            ExecutionException exception = assertThrows(ExecutionException.class, () -> future.get(4, TimeUnit.SECONDS));
            assertTrue(hasCancellation(exception), exception.toString());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void emptyDeclaredFileIsRejectedBeforeDownload() {
        server.stubFor(post(urlPathEqualTo("/v1/text-to-sfx")).willReturn(json("""
            {"task_id":"empty-1","status":"succeeded","audio":{"url":"/media/empty.mp3","content_type":"audio/mpeg","file_size":0}}
            """)));
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> sfx().prompt(Property.ofValue("Silence")).build().run(runContextFactory.of()));
        assertTrue(exception.getMessage().contains("empty audio file"));
        assertEquals(0, requestsTo("/media/empty.mp3"));
    }

    @SuppressWarnings("rawtypes")
    private GenerateMusicFromText.GenerateMusicFromTextBuilder music() {
        return GenerateMusicFromText.builder()
            .id("generate_music")
            .type(GenerateMusicFromText.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .pollInterval(Property.ofValue(Duration.ofMillis(20)))
            .waitTimeout(Property.ofValue(Duration.ofSeconds(3)));
    }

    @SuppressWarnings("rawtypes")
    private GenerateMusicFromVideo.GenerateMusicFromVideoBuilder videoMusic() {
        return GenerateMusicFromVideo.builder()
            .id("generate_music")
            .type(GenerateMusicFromVideo.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .pollInterval(Property.ofValue(Duration.ofMillis(20)))
            .waitTimeout(Property.ofValue(Duration.ofSeconds(3)));
    }

    @SuppressWarnings("rawtypes")
    private GenerateSfxFromVideo.GenerateSfxFromVideoBuilder sfxVideo() {
        return GenerateSfxFromVideo.builder()
            .id("generate_sfx")
            .type(GenerateSfxFromVideo.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .pollInterval(Property.ofValue(Duration.ofMillis(20)))
            .waitTimeout(Property.ofValue(Duration.ofSeconds(3)));
    }

    @SuppressWarnings("rawtypes")
    private GenerateSfxFromText.GenerateSfxFromTextBuilder sfx() {
        return GenerateSfxFromText.builder()
            .id("generate_sfx")
            .type(GenerateSfxFromText.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .pollInterval(Property.ofValue(Duration.ofMillis(20)))
            .waitTimeout(Property.ofValue(Duration.ofSeconds(3)));
    }

    private Trigger trigger(String taskId) {
        return Trigger.builder()
            .id("wait_for_task")
            .type(Trigger.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .interval(Duration.ofSeconds(30))
            .taskId(Property.ofValue(taskId))
            .build();
    }

    private Flow flow() {
        return Flow.builder()
            .id("on_sonilo_task_complete")
            .namespace("company.media")
            .tenantId(TenantService.MAIN_TENANT)
            .revision(1)
            .tasks(List.of())
            .variables(Map.of())
            .build();
    }

    private Optional<Execution> evaluate(Trigger trigger) throws Exception {
        return evaluate(trigger, runContextFactory.of(flow(), trigger));
    }

    private Optional<Execution> evaluate(Trigger trigger, RunContext runContext) throws Exception {
        Flow flow = flow();
        TriggerContext triggerContext = TriggerContext.builder()
            .tenantId(flow.getTenantId())
            .namespace(flow.getNamespace())
            .flowId(flow.getId())
            .triggerId(trigger.getId())
            .date(ZonedDateTime.now())
            .build();
        // of(flow, trigger) leaves storage unset. The scheduler attaches trigger storage before evaluate.
        runContext = runContextFactory.initializer().forScheduler((DefaultRunContext) runContext, triggerContext, trigger);
        ConditionContext conditionContext = ConditionContext.builder().runContext(runContext).flow(flow).build();
        return trigger.evaluate(conditionContext, triggerContext);
    }

    private void media(String path, String contentType, byte[] body) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200).withHeader("Content-Type", contentType).withBody(body)));
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static ResponseDefinitionBuilder ndjson(String... lines) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/x-ndjson").withBody(String.join("\n", lines) + "\n");
    }

    private static String chunk(byte[] data, Integer streamIndex) {
        return chunk(Base64.getEncoder().encodeToString(data), streamIndex);
    }

    private static String chunk(String encoded, Integer streamIndex) {
        String index = streamIndex == null ? "" : ",\"stream_index\":" + streamIndex;
        return "{\"type\":\"audio_chunk\",\"data\":\"" + encoded + "\"" + index + "}";
    }

    private LoggedRequest postRequest(String path) {
        return server.getAllServeEvents().stream()
            .map(ServeEvent::getRequest)
            .filter(request -> String.valueOf(request.getMethod()).contains("POST"))
            .filter(request -> request.getUrl().contains(path))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing POST " + path));
    }

    private LoggedRequest mediaRequest(String path) {
        return server.getAllServeEvents().stream()
            .map(ServeEvent::getRequest)
            .filter(request -> request.getUrl().contains(path))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing " + path));
    }

    private long requestsTo(String path) {
        return server.getAllServeEvents().stream().filter(event -> event.getRequest().getUrl().contains(path)).count();
    }

    private static void assertMultipart(LoggedRequest request) {
        String contentType = request.getHeader("Content-Type");
        assertNotNull(contentType);
        assertTrue(contentType.startsWith("multipart/form-data"), contentType);
        assertTrue(contentType.contains("boundary"), contentType);
        assertEquals("Bearer test-token", request.getHeader("Authorization"));
    }

    private static byte[] read(RunContext runContext, URI uri) throws Exception {
        try (InputStream input = runContext.storage().getFile(uri)) {
            return input.readAllBytes();
        }
    }

    private static void assertMessage(String fragment, ThrowingRunnable action) {
        Exception exception = assertThrows(Exception.class, action::run);
        assertTrue(exception.getMessage() != null && exception.getMessage().contains(fragment), exception.toString());
    }

    private static boolean hasCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.util.concurrent.CancellationException cancellation && cancellation.getMessage() != null && cancellation.getMessage().contains("killed")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
