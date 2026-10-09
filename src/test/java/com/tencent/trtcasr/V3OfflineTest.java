package com.tencent.trtcasr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.ErrorCodes;
import com.tencent.trtcasr.v3.AudioURLItem;
import com.tencent.trtcasr.v3.CreateTranscriptionRequest;
import com.tencent.trtcasr.v3.FileRecognizer;
import com.tencent.trtcasr.v3.SentenceRecognizer;
import com.tencent.trtcasr.v3.SpeakerRole;
import com.tencent.trtcasr.v3.TranscribeRequest;
import com.tencent.trtcasr.v3.TranscriptionStatus;
import com.tencent.trtcasr.v3.Wire;

/** v3 offline interface tests (transcribe / create / describe). */
public class V3OfflineTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static com.tencent.trtcasr.common.Credential credential() {
        return Wire.newCredential(1400000000, "test-secret");
    }

    @Test
    public void transcribeWireFormat() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> {
            assertEquals("POST", req.method);
            assertEquals("/v3/transcribe", req.path, "v3 has no query string");
            assertTrue(req.header("X-TRTC-UserSig") == null,
                    "v3 carries identity in the body, not headers");
            JsonNode body;
            try {
                body = M.readTree(req.body);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            JsonNode auth = body.path("auth");
            assertEquals("1400000000", auth.path("sdkappid").asText());
            assertFalse(auth.path("usersig").asText("").isEmpty());
            assertFalse(auth.path("request_id").asText("").isEmpty());
            assertEquals(3, auth.size(), "auth must only carry sdkappid, usersig, request_id");

            JsonNode params = body.path("params");
            assertEquals("16k_zh_en", params.path("engine_model_type").asText());
            assertEquals(1, params.path("source_type").asInt());
            assertEquals("pcm", params.path("voice_format").asText());
            assertEquals(0, params.path("needvad").asInt(), "explicit 0 must be honored");
            assertEquals("zh", params.path("language").asText());
            assertEquals("cG0tZGF0YQ==", params.path("data").asText());
            assertEquals(8, params.path("data_len").asInt());
            assertEquals("java", params.path("sdk_info").path("sdk_lang").asText());
            for (String bad : new String[] {"EngSerViceType", "SourceType", "VoiceFormat"}) {
                assertFalse(params.has(bad), bad + " must not appear");
            }
            return MockHttpServer.MockResponse.json(
                    "{\"code\":0,\"message\":\"success\",\"request_id\":\"req-1\","
                            + "\"result\":\"你好世界\",\"audio_duration\":1500,\"language\":\"zh\","
                            + "\"word_list\":[{\"word\":\"你好\",\"start_time\":0,\"end_time\":500},"
                            + "{\"word\":\"世界\",\"start_time\":500,\"end_time\":1500}]}");
        })) {
            SentenceRecognizer r = new SentenceRecognizer(credential());
            r.setEndpoint(server.url());
            TranscribeRequest req = new TranscribeRequest();
            req.engineModelType = "16k_zh_en";
            req.sourceType = Wire.SOURCE_TYPE_DATA;
            req.voiceFormat = "pcm";
            req.data = "cG0tZGF0YQ==";
            req.dataLen = 8;
            req.needvad = 0;
            req.language = "zh";
            req.wordInfo = 1;
            com.tencent.trtcasr.v3.TranscribeResponse resp = r.recognize(req);

            assertEquals(0, resp.code);
            assertEquals("你好世界", resp.result);
            assertEquals(1500, resp.audioDuration);
            assertEquals("req-1", resp.requestId);
            assertEquals(2, resp.wordList.size());
            assertEquals("你好", resp.wordList.get(0).word);
            assertEquals(0, resp.wordList.get(0).startTime);
            assertEquals(500, resp.wordList.get(0).endTime);
        }
    }

    @Test
    public void transcribeAuthErrorIsBodyCodeHttp200() throws Exception {
        try (MockHttpServer server = new MockHttpServer(
                req -> MockHttpServer.MockResponse.json(
                        "{\"code\":4002,\"message\":\"auth failed\",\"request_id\":\"req-x\"}"))) {
            SentenceRecognizer r = new SentenceRecognizer(credential());
            r.setEndpoint(server.url());
            try {
                r.recognizeURL("https://example.com/a.wav", "wav", "16k_zh_en");
                fail("must fail with the body code");
            } catch (ASRException e) {
                assertEquals(4002, e.getCode());
                assertTrue(e.getRawMessage().contains("req-x"));
            }
        }
    }

    @Test
    public void transcribeNonV3JsonBodyIsNotSuccess() throws Exception {
        try (MockHttpServer server = new MockHttpServer(
                req -> new MockHttpServer.MockResponse(502, "{\"error\":\"upstream unavailable\"}"))) {
            SentenceRecognizer r = new SentenceRecognizer(credential());
            r.setEndpoint(server.url());
            try {
                r.recognizeURL("https://example.com/a.wav", "wav", "16k_zh_en");
                fail("non-v3 body must fail");
            } catch (ASRException e) {
                assertEquals(ErrorCodes.SERVER_ERROR, e.getCode());
                assertTrue(e.getRawMessage().contains("502"));
            }
        }
    }

    @Test
    public void transcribeLocalValidation() throws Exception {
        SentenceRecognizer r = new SentenceRecognizer(credential());
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.recognizeData(new byte[0], "pcm", "16k_zh_en"));
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.recognize(new TranscribeRequest()));
        TranscribeRequest badNeedvad = new TranscribeRequest();
        badNeedvad.engineModelType = "16k_zh_en";
        badNeedvad.sourceType = Wire.SOURCE_TYPE_URL;
        badNeedvad.voiceFormat = "wav";
        badNeedvad.url = "https://example.com/a.wav";
        badNeedvad.needvad = 2;
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.recognize(badNeedvad));
        TranscribeRequest badRate = new TranscribeRequest();
        badRate.engineModelType = "16k_zh_en";
        badRate.sourceType = Wire.SOURCE_TYPE_URL;
        badRate.voiceFormat = "wav";
        badRate.url = "https://example.com/a.wav";
        badRate.inputSampleRate = 16000;
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.recognize(badRate));
        // nil request must raise, not NPE.
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.recognizeDataWithOptions(
                new byte[] {1}, null));
    }

    @Test
    public void createTaskWireFormatAndAudioURLs() throws Exception {
        AtomicReference<Throwable> handlerError = new AtomicReference<>();
        try (MockHttpServer server = new MockHttpServer(req -> {
            try {
                assertEquals("/v3/create_transcription", req.path);
                JsonNode params = M.readTree(req.body).path("params");
                if (params.has("audio_urls")) {
                    assertEquals(2, params.path("audio_urls").size());
                    assertEquals(0, params.path("audio_urls").get(0).path("index").asInt());
                    assertFalse(params.has("url"), "distributed mode must omit url");
                    return MockHttpServer.MockResponse.json(
                            "{\"code\":0,\"message\":\"success\",\"transcription_id\":\"tid-d\"}");
                }
                assertEquals(
                        "[{\"role_name\":\"teacher\",\"audio_url\":\"https://example.com/t.wav\"}]",
                        params.path("speaker_roles").toString());
                return MockHttpServer.MockResponse.json(
                        "{\"code\":0,\"message\":\"success\",\"transcription_id\":\"tid-abc\"}");
            } catch (Throwable t) {
                handlerError.set(t);
                return MockHttpServer.MockResponse.json(
                        "{\"code\":0,\"message\":\"success\",\"transcription_id\":\"tid-fallback\"}");
            }
        })) {
            FileRecognizer r = new FileRecognizer(credential());
            r.setEndpoint(server.url());

            CreateTranscriptionRequest req = new CreateTranscriptionRequest();
            req.engineModelType = "16k_zh_en";
            req.channelNum = 1;
            req.resTextFormat = 1;
            req.sourceType = Wire.SOURCE_TYPE_URL;
            req.url = "https://example.com/a.wav";
            req.speakerDiarization = Wire.SPEAKER_DIARIZATION_VOICEPRINT;
            req.speakerRoles = Arrays.asList(
                    new SpeakerRole("teacher", "https://example.com/t.wav"));
            req.voiceprintIds = Arrays.asList("vp-1");
            assertEquals("tid-abc", r.createTask(req));
            Throwable err = handlerError.get();
            if (err != null) {
                err.printStackTrace();
            }
            assertNull(handlerError.get(), "handler must not throw");

            CreateTranscriptionRequest dist = new CreateTranscriptionRequest();
            dist.engineModelType = "16k_zh_en";
            dist.channelNum = 1;
            dist.resTextFormat = 1;
            dist.sourceType = Wire.SOURCE_TYPE_URL; // zero value; url/data empty
            dist.speakerDiarization = Wire.SPEAKER_DIARIZATION_CLUSTER;
            dist.audioUrls = Arrays.asList(
                    new AudioURLItem(0, "https://example.com/a.wav", "a"),
                    new AudioURLItem(1, "https://example.com/b.wav"));
            assertEquals("tid-d", r.createTask(dist));
            assertNull(handlerError.get(), "handler must not throw");
        }
    }

    @Test
    public void createTaskAudioURLsReject() throws Exception {
        FileRecognizer r = new FileRecognizer(credential());

        CreateTranscriptionRequest withUrl = base();
        withUrl.url = "https://example.com/x.wav";
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.createTask(withUrl));

        CreateTranscriptionRequest withData = base();
        withData.data = "AAAA";
        withData.dataLen = 3;
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.createTask(withData));

        CreateTranscriptionRequest badType = base();
        badType.sourceType = Wire.SOURCE_TYPE_DATA;
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.createTask(badType));

        CreateTranscriptionRequest negIndex = base();
        negIndex.audioUrls.get(0).setIndex(-1);
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.createTask(negIndex));

        CreateTranscriptionRequest dupIndex = base();
        dupIndex.audioUrls.add(new AudioURLItem(0, "https://example.com/b.wav"));
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.createTask(dupIndex));

        CreateTranscriptionRequest emptyUrl = base();
        emptyUrl.audioUrls.get(0).setUrl(" ");
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.createTask(emptyUrl));

        CreateTranscriptionRequest badScheme = base();
        badScheme.audioUrls.get(0).setUrl("ftp://x/a.wav");
        assertThrowsCode(ErrorCodes.INVALID_PARAM, () -> r.createTask(badScheme));
    }

    private static CreateTranscriptionRequest base() {
        CreateTranscriptionRequest req = new CreateTranscriptionRequest();
        req.engineModelType = "16k_zh_en";
        req.channelNum = 1;
        req.resTextFormat = 1;
        req.audioUrls = new ArrayList<>(Collections.singletonList(
                new AudioURLItem(0, "https://example.com/a.wav")));
        return req;
    }

    @Test
    public void describeTaskPollAndMapping() throws Exception {
        int[] calls = {0};
        try (MockHttpServer server = new MockHttpServer(req -> {
            assertEquals("/v3/describe_transcription", req.path);
            if (calls[0]++ == 0) {
                return MockHttpServer.MockResponse.json(
                        "{\"code\":0,\"request_id\":\"r1\",\"transcription_id\":\"t1\","
                                + "\"status\":1,\"status_str\":\"executing\",\"progress\":40}");
            }
            return MockHttpServer.MockResponse.json(
                    "{\"code\":0,\"request_id\":\"r2\",\"transcription_id\":\"t1\",\"status\":2,"
                            + "\"status_str\":\"success\",\"progress\":100,\"audio_duration\":6.312,"
                            + "\"result\":\"全文\",\"result_detail\":[{\"final_sentence\":\"第一句话。\","
                            + "\"start_ms\":0,\"end_ms\":1200,\"words_num\":1,"
                            + "\"words\":[{\"word\":\"第一句\",\"start_time\":0,\"end_time\":900}],"
                            + "\"speaker_id\":1,\"speaker_role_name\":\"teacher\",\"channel_id\":0,"
                            + "\"speech_speed\":3.2,\"language\":\"zh\",\"language_b47\":\"zh-CN\"}]}");
        })) {
            FileRecognizer r = new FileRecognizer(credential());
            r.setEndpoint(server.url());
            TranscriptionStatus status = r.waitForResultWithInterval("t1",
                    Duration.ofMillis(10), Duration.ofSeconds(5));

            assertEquals(Wire.TASK_STATUS_SUCCESS, status.status);
            assertEquals("全文", status.result);
            assertEquals(6.312, status.audioDuration, 1e-9);
            assertEquals(1, status.resultDetail.size());
            TranscriptionStatus.SentenceDetail d = status.resultDetail.get(0);
            assertEquals("第一句话。", d.finalSentence);
            assertEquals(1, d.speakerId);
            assertEquals("teacher", d.speakerRoleName);
            assertEquals("第一句", d.words.get(0).word);
            assertEquals(0, d.words.get(0).startTime);
            assertEquals(900, d.words.get(0).endTime);
            assertEquals("zh-CN", d.languageB47);
        }
    }

    @Test
    public void describeTaskOwnershipError() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> new MockHttpServer.MockResponse(403,
                "{\"code\":4002,\"message\":\"transcription_id does not belong to this sdkappid\"}"))) {
            FileRecognizer r = new FileRecognizer(credential());
            r.setEndpoint(server.url());
            try {
                r.describeTask("tid-foreign");
                fail("cross-account query must fail");
            } catch (ASRException e) {
                assertEquals(4002, e.getCode());
            }
        }
    }

    @Test
    public void createTaskMissingTranscriptionId() throws Exception {
        try (MockHttpServer server = new MockHttpServer(
                req -> MockHttpServer.MockResponse.json("{\"code\":0,\"message\":\"success\"}"))) {
            FileRecognizer r = new FileRecognizer(credential());
            r.setEndpoint(server.url());
            assertThrowsCode(ErrorCodes.SERVER_ERROR,
                    () -> r.createTaskFromURL("https://example.com/a.wav", "16k_zh_en"));
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertThrowsCode(int code, ThrowingRunnable runnable) {
        try {
            runnable.run();
            fail("expected ASRException with code " + code);
        } catch (ASRException e) {
            assertEquals(code, e.getCode());
        } catch (Exception e) {
            fail("unexpected exception: " + e);
        }
    }
}
