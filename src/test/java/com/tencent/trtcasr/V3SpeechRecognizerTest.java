package com.tencent.trtcasr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.trtcasr.asr.SpeechRecognitionListener;
import com.tencent.trtcasr.asr.SpeechRecognitionResponse;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.ErrorCodes;
import com.tencent.trtcasr.v3.AudioURLItem;
import com.tencent.trtcasr.v3.CreateTranscriptionRequest;
import com.tencent.trtcasr.v3.FileRecognizer;
import com.tencent.trtcasr.v3.SentenceRecognizer;
import com.tencent.trtcasr.v3.SpeakerRole;
import com.tencent.trtcasr.v3.SpeechRecognizer;
import com.tencent.trtcasr.v3.TranscribeRequest;
import com.tencent.trtcasr.v3.Wire;

/** v3 tests: start-frame wire, sync ack handling, offline interfaces. */
public class V3SpeechRecognizerTest {
    private static final ObjectMapper M = new ObjectMapper();

    /** A 64-hex speaker_context_id, as issued by the server. */
    private static final String CONTEXT_ID =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static com.tencent.trtcasr.common.Credential credential() {
        return Wire.newCredential(1400000000, "test-secret");
    }

    private static String ack(String voiceId) {
        return "{\"code\":0,\"message\":\"success\",\"voice_id\":\"" + voiceId + "\"}";
    }

    private static String resultFrame(int sliceType, int finalFlag, String text) {
        return "{\"code\":0,\"message\":\"success\",\"voice_id\":\"v1\",\"final\":" + finalFlag
                + ",\"result\":{\"slice_type\":" + sliceType + ",\"index\":0,\"start_time\":0,"
                + "\"end_time\":1000,\"voice_text_str\":\"" + text + "\"}}";
    }

    /** Collects callback events; thread-safe via the SDK's callback thread. */
    private static class RecordingListener implements SpeechRecognitionListener {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        int begins;
        int changes;
        int ends;
        int completes;
        int fails;
        String lastEndText = "";
        String lastFailCode = "";
        final java.util.List<SpeechRecognitionResponse> startResponses =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void onRecognitionStart(SpeechRecognitionResponse response) {
            startResponses.add(response);
        }

        @Override
        public synchronized void onSentenceBegin(SpeechRecognitionResponse response) {
            begins++;
        }

        @Override
        public synchronized void onRecognitionResultChange(SpeechRecognitionResponse response) {
            changes++;
        }

        @Override
        public synchronized void onSentenceEnd(SpeechRecognitionResponse response) {
            ends++;
            lastEndText = response.getResult().getVoiceTextStr();
        }

        @Override
        public void onRecognitionComplete(SpeechRecognitionResponse response) {
            completes++;
            done.countDown();
        }

        @Override
        public synchronized void onFail(SpeechRecognitionResponse response,
                com.tencent.trtcasr.common.ASRException error) {
            fails++;
            failure.set(error);
            lastFailCode = String.valueOf(error.getCode());
        }
    }

    /** Server helper mirroring the real worker: ack, wait for the end signal,
     * then send the terminal frame (a final sent earlier would complete the
     * session before stop() runs). */
    private static void serveAckThenFinalAfterEnd(MiniWebSocketServer.Session ws)
            throws Exception {
        ws.read(); // start frame
        ws.sendText(ack("v1"));
        serveFinalAfterEnd(ws);
    }

    /** Waits for the client's end signal, then sends the terminal frame. */
    private static void serveFinalAfterEnd(MiniWebSocketServer.Session ws) throws Exception {
        MiniWebSocketServer.Frame f;
        while ((f = ws.read()) != null) {
            if (f.isText() && f.text().contains("\"end\"")) {
                break;
            }
        }
        ws.sendText(resultFrame(2, 1, "done"));
        while (ws.read() != null) {
            // keep the session open
        }
    }

    @Test
    public void startFrameWireFormat() throws Exception {
        AtomicReference<String> startFrame = new AtomicReference<>();
        try (MiniWebSocketServer server = new MiniWebSocketServer(ws -> {
            MiniWebSocketServer.Frame start = ws.read();
            assertNotNull(start, "start frame must arrive");
            startFrame.set(start.text());
            ws.sendText(ack("voice-1"));
            serveAckThenFinalAfterEnd(ws);
        })) {
            RecordingListener listener = new RecordingListener();
            SpeechRecognizer r = new SpeechRecognizer(credential(), "16k_zh_en", listener);
            r.setEndpoint(server.url());
            r.setVoiceId("voice-1");
            r.setHotwordList("深度学习|10");
            r.setVadLevel(0);
            r.setNoiseThreshold(1.5);
            r.setFilterEmptyResult(0);
            r.setConvertNumMode(0); // explicit 0 must be sent on v3
            r.setSpeakerDiarization(Wire.SPEAKER_DIARIZATION_VOICEPRINT);
            r.setSpeakerRoles(Arrays.asList(
                    new SpeakerRole("teacher", "https://example.com/t.wav")));
            r.setVoiceprintIds(Arrays.asList("vp-1"));
            r.setEnableSpeakerContext(Wire.SPEAKER_CONTEXT_SYNC);
            r.setSpeakerContextId(CONTEXT_ID);
            r.start();
            r.write(new byte[1280]);
            r.stop();
            assertTrue(listener.done.await(3, TimeUnit.SECONDS), "session must complete");

            // Handshake URL: path /asr/v3, query carries only voice_id.
            assertEquals("/asr/v3?voice_id=voice-1", server.requestTarget());

            // Start frame structure.
            JsonNode frame = M.readTree(startFrame.get());
            assertEquals("start", frame.path("type").asText());
            JsonNode auth = frame.path("auth");
            assertEquals("1400000000", auth.path("sdkappid").asText());
            assertFalse(auth.path("usersig").asText("").isEmpty());
            assertEquals(2, auth.size(), "auth must only carry sdkappid and usersig");

            JsonNode params = frame.path("params");
            assertEquals("voice-1", params.path("voice_id").asText());
            assertEquals("16k_zh_en", params.path("engine_model_type").asText());
            assertEquals(1, params.path("voice_format").asInt());
            assertEquals(1, params.path("needvad").asInt());
            assertEquals(0, params.path("convert_num_mode").asInt());
            assertEquals(0, params.path("filter_empty_result").asInt());
            assertEquals(0, params.path("vad_level").asInt());
            assertEquals(1.5, params.path("noise_threshold").asDouble(), 1e-9);
            assertEquals("深度学习|10", params.path("hotword_list").asText());
            assertEquals(3, params.path("speaker_diarization").asInt());
            assertEquals(M.readTree("[\"vp-1\"]"), params.path("voiceprint_ids"));
            // Speaker context ("断点续传"): the mode and the id travel in params.
            assertEquals(Wire.SPEAKER_CONTEXT_SYNC,
                    params.path("enable_speaker_context").asInt());
            assertEquals(CONTEXT_ID, params.path("speaker_context_id").asText());
            // speaker_roles elements are snake_case, not the v2 CamelCase wire.
            assertEquals(
                    M.readTree("[{\"role_name\":\"teacher\",\"audio_url\":\"https://example.com/t.wav\"}]"),
                    params.path("speaker_roles"));
            assertTrue(params.path("context").isMissingNode());
            assertEquals("java", params.path("sdk_info").path("sdk_lang").asText());
            assertNull(server.handlerError());
        }
    }

    @Test
    public void speakerContextIdOmittedWhenModeOff() throws Exception {
        AtomicReference<String> startFrame = new AtomicReference<>();
        try (MiniWebSocketServer server = new MiniWebSocketServer(ws -> {
            MiniWebSocketServer.Frame start = ws.read();
            assertNotNull(start, "start frame must arrive");
            startFrame.set(start.text());
            ws.sendText(ack("v1"));
            serveFinalAfterEnd(ws);
        })) {
            RecordingListener listener = new RecordingListener();
            SpeechRecognizer r = new SpeechRecognizer(credential(), "16k_zh_en", listener);
            r.setEndpoint(server.url());
            r.setSpeakerDiarization(Wire.SPEAKER_DIARIZATION_CLUSTER);
            r.setEnableSpeakerContext(Wire.SPEAKER_CONTEXT_SYNC);
            r.setSpeakerContextId(CONTEXT_ID);
            r.setEnableSpeakerContext(Wire.SPEAKER_CONTEXT_OFF);
            r.start();
            r.stop();
            assertTrue(listener.done.await(3, TimeUnit.SECONDS), "session must complete");

            JsonNode params = M.readTree(startFrame.get()).path("params");
            assertTrue(params.path("enable_speaker_context").isMissingNode());
            assertTrue(params.path("speaker_context_id").isMissingNode());
            assertNull(server.handlerError());
        }
    }

    @Test
    public void speakerContinueHandshake() throws Exception {
        for (String[] c : new String[][] {
                // status, enable mode (null = off), expected status, expects id
                {"{\"continue_status\":\"resumed\",\"speaker_context_id\":\"" + CONTEXT_ID + "\"}",
                        "1", "resumed", "true"},
                {"{\"continue_status\":\"fresh\",\"speaker_context_id\":\"" + CONTEXT_ID + "\"}",
                        "1", "fresh", "true"},
                // Async mode answers before the snapshot is applied: id only.
                {"{\"speaker_context_id\":\"" + CONTEXT_ID + "\"}", "2", "", "true"},
                {"null", null, null, null},
        }) {
            String speakerContinueJson = c[0];
            String mode = c[1];
            String wantStatus = c[2];
            boolean expectId = c[3] != null;

            try (MiniWebSocketServer server = new MiniWebSocketServer(ws -> {
                ws.read(); // start frame
                ws.sendText("{\"code\":0,\"message\":\"success\",\"voice_id\":\"v1\","
                        + "\"speaker_continue\":" + speakerContinueJson + "}");
                serveFinalAfterEnd(ws);
            })) {
                RecordingListener listener = new RecordingListener();
                SpeechRecognizer r = new SpeechRecognizer(credential(), "16k_zh_en", listener);
                r.setEndpoint(server.url());
                if (mode != null) {
                    r.setSpeakerDiarization(Wire.SPEAKER_DIARIZATION_CLUSTER);
                    r.setEnableSpeakerContext(Integer.parseInt(mode));
                }
                r.start();

                // The getter is usable as soon as start() returns.
                com.tencent.trtcasr.asr.SpeakerContinue sc = r.getSpeakerContinue();
                if (!expectId) {
                    assertNull(sc, "context off must yield no handshake result");
                } else {
                    assertNotNull(sc);
                    assertEquals(wantStatus, sc.getContinueStatus());
                    assertEquals(CONTEXT_ID, sc.getSpeakerContextId());
                }

                r.write(new byte[1280]);
                r.stop();
                assertTrue(listener.done.await(3, TimeUnit.SECONDS), "session must complete");

                // The same block is delivered on onRecognitionStart.
                assertTrue(listener.startResponses.size() >= 1, "onRecognitionStart must fire");
                com.tencent.trtcasr.asr.SpeakerContinue delivered =
                        listener.startResponses.get(0).getSpeakerContinue();
                if (!expectId) {
                    assertNull(delivered);
                } else {
                    assertNotNull(delivered);
                    assertEquals(CONTEXT_ID, delivered.getSpeakerContextId());
                }
                assertNull(server.handlerError());
            }
        }
    }

    @Test
    public void startAuthErrorIsSynchronous() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(ws -> {
            ws.read(); // start frame
            ws.sendText("{\"code\":4002,\"message\":\"auth failed\",\"voice_id\":\"v1\"}");
            while (ws.read() != null) {
            }
        })) {
            RecordingListener listener = new RecordingListener();
            SpeechRecognizer r = new SpeechRecognizer(credential(), "16k_zh_en", listener);
            r.setEndpoint(server.url());
            try {
                r.start();
                fail("start must fail synchronously");
            } catch (ASRException e) {
                assertEquals(4002, e.getCode());
                assertTrue(e.getRawMessage().contains("auth failed"));
            }
            // The failed start leaves no usable session.
            try {
                r.write(new byte[16]);
                fail("write after failed start must fail");
            } catch (ASRException e) {
                assertEquals(ErrorCodes.NOT_STARTED, e.getCode());
            }
            assertNull(server.handlerError());
        }
    }

    @Test
    public void start4001ErrorIsSynchronous() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(ws -> {
            ws.read();
            ws.sendText("{\"code\":4001,\"message\":\"v3 interface not enabled\",\"voice_id\":\"v1\"}");
            while (ws.read() != null) {
            }
        })) {
            SpeechRecognizer r = new SpeechRecognizer(credential(), "16k_zh_en",
                    new RecordingListener());
            r.setEndpoint(server.url());
            try {
                r.start();
                fail("start must fail synchronously");
            } catch (ASRException e) {
                assertEquals(4001, e.getCode());
            }
            assertNull(server.handlerError());
        }
    }

    @Test
    public void normalFlowDispatchesEvents() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(ws -> {
            ws.read();
            ws.sendText(ack("v1"));
            ws.sendText(resultFrame(0, 0, ""));
            ws.sendText(resultFrame(1, 0, "你好"));
            ws.sendText(resultFrame(2, 0, "你好。"));
            // Terminal frame only after the client's end signal.
            MiniWebSocketServer.Frame f;
            while ((f = ws.read()) != null) {
                if (f.isText() && f.text().contains("\"end\"")) {
                    break;
                }
            }
            ws.sendText(resultFrame(2, 1, "你好。"));
            while (ws.read() != null) {
            }
        })) {
            RecordingListener listener = new RecordingListener();
            SpeechRecognizer r = new SpeechRecognizer(credential(), "16k_zh_en", listener);
            r.setEndpoint(server.url());
            r.start();
            r.write(new byte[1280]);
            // stop() sends the end signal; the server answers with the
            // terminal frame, which fires onRecognitionComplete before stop
            // returns.
            r.stop();
            assertTrue(listener.done.await(3, TimeUnit.SECONDS), "session must complete");
            assertTrue(listener.begins >= 1);
            assertTrue(listener.changes >= 1);
            assertTrue(listener.ends >= 2, "standalone end + final frame both dispatch");
            assertEquals(1, listener.completes);
            assertEquals(0, listener.fails);
            assertNull(server.handlerError());
        }
    }

    @Test
    public void oversizedAudioFrameFailsLocally() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(ws -> {
            ws.read();
            ws.sendText(ack("v1"));
            serveAckThenFinalAfterEnd(ws);
        })) {
            SpeechRecognizer r = new SpeechRecognizer(credential(), "16k_zh_en",
                    new RecordingListener());
            r.setEndpoint(server.url());
            r.start();
            try {
                r.write(new byte[Wire.STREAM_FRAME_MAX_BYTES + 1]);
                fail("oversized frame must fail locally");
            } catch (ASRException e) {
                assertEquals(ErrorCodes.INVALID_PARAM, e.getCode());
            }
            r.stop();
            assertNull(server.handlerError());
        }
    }

    @Test
    public void localValidationRejectsOutOfRangeOptions() {
        String longVoiceId = "x".repeat(Wire.MAX_VOICE_ID_LEN + 1);
        record Case(String name, java.util.function.Consumer<SpeechRecognizer> apply) {
        }
        List<Case> cases = Arrays.asList(
                new Case("voice_id too long", r -> r.setVoiceId(longVoiceId)),
                new Case("max_speak_time too small", r -> r.setMaxSpeakTime(1000)),
                new Case("max_speak_time too large", r -> r.setMaxSpeakTime(90001)),
                new Case("vad_silence_time too small", r -> r.setVadSilenceTime(100)),
                new Case("vad_silence_time too large", r -> r.setVadSilenceTime(2001)),
                new Case("needvad invalid", r -> r.setNeedVad(2)),
                new Case("convert_num_mode invalid", r -> r.setConvertNumMode(2)),
                new Case("filter_dirty invalid", r -> r.setFilterDirty(3)),
                new Case("filter_modal invalid", r -> r.setFilterModal(3)),
                new Case("filter_punc invalid", r -> r.setFilterPunc(2)),
                new Case("word_info invalid", r -> r.setWordInfo(3)),
                new Case("word_with_space invalid", r -> r.setWordWithSpace(2)),
                new Case("voice_format invalid", r -> r.setVoiceFormat(2)),
                new Case("input_sample_rate invalid", r -> r.setInputSampleRate(16000)),
                new Case("filter_empty_result invalid", r -> r.setFilterEmptyResult(2)),
                new Case("vad_level invalid", r -> r.setVadLevel(2)),
                new Case("noise_threshold out of range", r -> r.setNoiseThreshold(4.1)),
                new Case("diarization invalid", r -> r.setSpeakerDiarization(2)),
                new Case("enable_speaker_context invalid", r -> {
                    r.setSpeakerDiarization(Wire.SPEAKER_DIARIZATION_CLUSTER);
                    r.setEnableSpeakerContext(3);
                }),
                new Case("enable_speaker_context without diarization",
                        r -> r.setEnableSpeakerContext(Wire.SPEAKER_CONTEXT_SYNC)));
        for (Case tc : cases) {
            SpeechRecognizer r = new SpeechRecognizer(credential(), "16k_zh_en",
                    new RecordingListener());
            r.setEndpoint("ws://127.0.0.1:1"); // unreachable; validation fails first
            tc.apply().accept(r);
            try {
                r.start();
                fail(tc.name() + " must fail locally");
            } catch (ASRException e) {
                assertEquals(ErrorCodes.INVALID_PARAM, e.getCode(), tc.name());
            }
        }

        // Boundary values must pass validation (they then fail at dial,
        // which must not be INVALID_PARAM).
        SpeechRecognizer r = new SpeechRecognizer(credential(), "16k_zh_en",
                new RecordingListener());
        r.setEndpoint("ws://127.0.0.1:1");
        r.setMaxSpeakTime(90000);
        r.setVadSilenceTime(240);
        r.setVoiceFormat(12);
        r.setWordInfo(100);
        try {
            r.start();
        } catch (ASRException e) {
            assertFalse(e.getCode() == ErrorCodes.INVALID_PARAM,
                    "valid options must not be rejected locally: " + e);
        }
    }
}
