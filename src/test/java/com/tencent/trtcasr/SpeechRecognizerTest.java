package com.tencent.trtcasr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.tencent.trtcasr.asr.SpeechRecognitionListener;
import com.tencent.trtcasr.asr.SpeechRecognitionResponse;
import com.tencent.trtcasr.asr.SpeechRecognizer;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.common.ErrorCodes;
import com.tencent.trtcasr.common.SignatureParams;
import com.tencent.trtcasr.common.SpeakerRole;

/**
 * SpeechRecognizer lifecycle & concurrency tests against a local mock
 * WebSocket server, ported from the Go SDK's speech_recognizer_test.go.
 */
class SpeechRecognizerTest {

    private static Credential testCredential() {
        return new Credential(1300000000L, 1400000000L, "test-secret");
    }

    /** A listener that records events for assertions. */
    static class RecordingListener implements SpeechRecognitionListener {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch completeLatch = new CountDownLatch(1);
        final CountDownLatch failLatch = new CountDownLatch(1);
        volatile ASRException lastError;
        volatile SpeechRecognitionResponse lastFailResp;

        @Override
        public void onRecognitionStart(SpeechRecognitionResponse r) {
            events.add("start:" + r.getVoiceId());
        }

        @Override
        public void onSentenceBegin(SpeechRecognitionResponse r) {
            events.add("begin:" + r.getResult().getIndex());
        }

        @Override
        public void onRecognitionResultChange(SpeechRecognitionResponse r) {
            events.add("change:" + r.getResult().getVoiceTextStr());
        }

        @Override
        public void onSentenceEnd(SpeechRecognitionResponse r) {
            events.add("end:" + r.getResult().getVoiceTextStr());
        }

        @Override
        public void onRecognitionComplete(SpeechRecognitionResponse r) {
            events.add("complete:" + r.getFinalFlag());
            completeLatch.countDown();
        }

        @Override
        public void onFail(SpeechRecognitionResponse r, ASRException e) {
            events.add("fail:" + e.getCode());
            lastError = e;
            lastFailResp = r;
            failLatch.countDown();
        }

        long count(String prefix) {
            return events.stream().filter(e -> e.startsWith(prefix)).count();
        }
    }

    private static SpeechRecognizer newRecognizer(SpeechRecognitionListener l, String wsUrl) {
        SpeechRecognizer r = new SpeechRecognizer(testCredential(), "16k_zh_en", l);
        r.setEndpoint(wsUrl);
        r.setWriteTimeout(Duration.ofMillis(500));
        // Keep stop() fast: tests that don't specifically exercise the stop
        // timeout should not wait the 10s default.
        r.setStopTimeout(Duration.ofSeconds(2));
        return r;
    }

    /** A server handler that holds the session open and answers the end
     * signal with a final frame, so stop() returns promptly. */
    private static MiniWebSocketServer.Handler holdOpenAnsweringEnd() {
        return s -> {
            try {
                MiniWebSocketServer.Frame f;
                while ((f = s.read()) != null) {
                    if (f.isText() && f.text().equals("{\"type\":\"end\"}")) {
                        s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\",\"final\":1,"
                                + "\"result\":{\"slice_type\":2}}");
                        // Keep the socket up until the client disconnects.
                        // Returning here closes TCP in the same instant as the
                        // final frame; a fast runner reports the close before
                        // onText, and the session ends with no complete.
                        while (s.read() != null) {
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Test
    void writeBeforeStartReturnsNotStarted() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
        })) {
            SpeechRecognizer r = newRecognizer(new RecordingListener(), server.url());
            ASRException err = assertThrows(ASRException.class, () -> r.write(new byte[]{1}));
            assertEquals(ErrorCodes.NOT_STARTED, err.getCode());
        }
    }

    @Test
    void stopBeforeStartReturnsNotStarted() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
        })) {
            SpeechRecognizer r = newRecognizer(new RecordingListener(), server.url());
            ASRException err = assertThrows(ASRException.class, r::stop);
            assertEquals(ErrorCodes.NOT_STARTED, err.getCode());
        }
    }

    @Test
    void startTwiceReturnsAlreadyStarted() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(holdOpenAnsweringEnd())) {
            SpeechRecognizer r = newRecognizer(new RecordingListener(), server.url());
            r.start();
            ASRException err = assertThrows(ASRException.class, r::start);
            assertEquals(ErrorCodes.ALREADY_STARTED, err.getCode());
            r.stop();
            server.join(3000);
        }
    }

    @Test
    void startRejectsInvalidOptionsAndStaysReusable() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(holdOpenAnsweringEnd())) {
            SpeechRecognizer r = newRecognizer(new RecordingListener(), server.url());
            r.setSpeakerDiarization(2); // unsupported
            ASRException err = assertThrows(ASRException.class, r::start);
            assertEquals(ErrorCodes.INVALID_PARAM, err.getCode());
            assertTrue(err.getMessage().contains("SpeakerDiarization must be 0"));

            // A rejected start leaves the recognizer reusable after fixing options.
            r.setSpeakerDiarization(0);
            r.start();
            r.stop();
            server.join(3000);
        }
    }

    @Test
    void startRejectsOutOfRangeNoiseThreshold() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
        })) {
            SpeechRecognizer r = newRecognizer(new RecordingListener(), server.url());
            r.setNoiseThreshold(5.0);
            ASRException err = assertThrows(ASRException.class, r::start);
            assertTrue(err.getMessage().contains("NoiseThreshold must be between"));
        }
    }

    @Test
    void startRejectsRolesWithoutVoiceprintMode() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
        })) {
            SpeechRecognizer r = newRecognizer(new RecordingListener(), server.url());
            r.setSpeakerDiarization(1);
            r.setSpeakerRoles(List.of(new SpeakerRole("teacher", "https://example.com/a.wav")));
            ASRException err = assertThrows(ASRException.class, r::start);
            assertTrue(err.getMessage().contains("require SpeakerDiarization=3"));
        }
    }

    @Test
    void handshakeSendsAuthQueryParams() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(holdOpenAnsweringEnd())) {
            SpeechRecognizer r = newRecognizer(new RecordingListener(), server.url());
            r.setVoiceId("voice-handshake");
            r.start();

            long deadline = System.currentTimeMillis() + 2000;
            while (server.requestTarget() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            String target = server.requestTarget();
            assertNotNull(target);
            assertTrue(target.startsWith("/asr/v2/1300000000?"), target);
            for (String key : new String[]{"sdkappid=1400000000", "usersig=", "signature=",
                    "secretid=1300000000", "voice_id=voice-handshake",
                    "engine_model_type=16k_zh_en", "timestamp=", "expired=", "nonce="}) {
                assertTrue(target.contains(key), "missing " + key + " in " + target);
            }
            // SecretKey must never reach the wire.
            assertFalse(target.contains("test-secret"));

            r.stop();
            server.join(3000);
        }
    }

    @Test
    void fullSessionLifecycle() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            try {
                // Handshake ack: no result object — must not trigger sentence begin.
                s.sendText("{\"code\":0,\"message\":\"success\",\"voice_id\":\"v1\"}");
                s.sendText("{\"code\":0,\"message\":\"success\",\"voice_id\":\"v1\",\"message_id\":\"m1\","
                        + "\"result\":{\"slice_type\":0,\"index\":0,\"voice_text_str\":\"今天。\"}}");
                s.sendText("{\"code\":0,\"message\":\"success\",\"voice_id\":\"v1\",\"message_id\":\"m2\","
                        + "\"result\":{\"slice_type\":1,\"index\":0,\"voice_text_str\":\"今天天气\"}}");
                MiniWebSocketServer.Frame f;
                while ((f = s.read()) != null) {
                    if (f.isText() && f.text().equals("{\"type\":\"end\"}")) {
                        s.sendText("{\"code\":0,\"message\":\"success\",\"voice_id\":\"v1\",\"message_id\":\"m3\","
                                + "\"final\":1,\"result\":{\"slice_type\":2,\"index\":0,"
                                + "\"voice_text_str\":\"今天天气不错。\"}}");
                        while (s.read() != null) {
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })) {
            SpeechRecognizer r = newRecognizer(listener, server.url());
            r.setVoiceId("v1");
            r.start();
            r.write(new byte[]{0, 1, 2, 3});
            r.stop();

            assertTrue(listener.completeLatch.await(2, TimeUnit.SECONDS),
                    "should complete, events=" + listener.events + " err=" + listener.lastError);
            assertEquals(1, listener.count("start:"));
            // The handshake ack must NOT be dispatched as sentence begin.
            assertEquals(1, listener.count("begin:"));
            assertEquals(1, listener.count("change:"));
            assertEquals(1, listener.count("end:今天天气不错。"));
            assertEquals(1, listener.count("complete:"));
            assertEquals(0, listener.count("fail:"));

            // A late write must report not-running.
            ASRException err = assertThrows(ASRException.class, () -> r.write(new byte[]{9}));
            assertEquals(ErrorCodes.NOT_STARTED, err.getCode());

            server.join(3000);
        }
    }

    @Test
    void writeAndEndSignalReachServer() throws Exception {
        AtomicBoolean gotAudio = new AtomicBoolean();
        AtomicBoolean gotEnd = new AtomicBoolean();
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            try {
                MiniWebSocketServer.Frame f;
                while ((f = s.read()) != null) {
                    if (f.isBinary() && java.util.Arrays.equals(f.payload, new byte[]{1, 2, 3})) {
                        gotAudio.set(true);
                    }
                    if (f.isText() && f.text().equals("{\"type\":\"end\"}")) {
                        gotEnd.set(true);
                        s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\",\"final\":1,"
                                + "\"result\":{\"slice_type\":2}}");
                        // Keep the socket up until the client disconnects.
                        // Returning here closes TCP in the same instant as the
                        // final frame; a fast runner reports the close before
                        // onText, and the session ends with no complete.
                        while (s.read() != null) {
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })) {
            SpeechRecognizer r = newRecognizer(new RecordingListener(), server.url());
            r.start();
            r.write(new byte[]{1, 2, 3});
            r.stop();

            assertTrue(gotAudio.get(), "server should receive the audio frame");
            assertTrue(gotEnd.get(), "server should receive the end signal");
            server.join(3000);
        }
    }

    @Test
    void serverErrorTriggersOnFailAndStops() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            try {
                s.sendText("{\"code\":4006,\"message\":\"quota exceeded\",\"voice_id\":\"v1\",\"result\":{}}");
                while (s.read() != null) {
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })) {
            SpeechRecognizer r = newRecognizer(listener, server.url());
            r.start();

            assertTrue(listener.failLatch.await(2, TimeUnit.SECONDS), "should fail");
            assertNotNull(listener.lastError);
            assertEquals(4006, listener.lastError.getCode());
            assertNotNull(listener.lastFailResp, "OnFail should carry the response");

            // After a terminal error the recognizer is stopped.
            ASRException err = assertThrows(ASRException.class, () -> r.write(new byte[]{9}));
            assertEquals(ErrorCodes.NOT_STARTED, err.getCode());
            err = assertThrows(ASRException.class, r::stop);
            assertEquals(ErrorCodes.NOT_STARTED, err.getCode());
            server.join(3000);
        }
    }

    @Test
    void finalWithSliceZeroOnlyCompletes() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            try {
                s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\",\"final\":1,"
                        + "\"result\":{\"slice_type\":0,\"index\":0}}");
                while (s.read() != null) {
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })) {
            SpeechRecognizer r = newRecognizer(listener, server.url());
            r.start();

            assertTrue(listener.completeLatch.await(2, TimeUnit.SECONDS));
            assertEquals(0, listener.count("begin:"));
            assertEquals(0, listener.count("end:"));
            assertEquals(1, listener.count("complete:"));
            server.join(3000);
        }
    }

    @Test
    void malformedFrameIsNonTerminal() throws Exception {
        RecordingListener listener = new RecordingListener();
        CountDownLatch changeLatch = new CountDownLatch(1);
        SpeechRecognitionListener l = new RecordingListener() {
            @Override
            public void onRecognitionResultChange(SpeechRecognitionResponse r) {
                super.onRecognitionResultChange(r);
                changeLatch.countDown();
            }
        };
        ((RecordingListener) l).events.addAll(listener.events); // no-op, separate lists
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            try {
                s.sendText("not-json");
                s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\","
                        + "\"result\":{\"slice_type\":1,\"index\":0,\"voice_text_str\":\"hi\"}}");
                MiniWebSocketServer.Frame f;
                while ((f = s.read()) != null) {
                    if (f.isText() && f.text().equals("{\"type\":\"end\"}")) {
                        s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\",\"final\":1,"
                                + "\"result\":{\"slice_type\":2}}");
                        // Keep the socket up until the client disconnects.
                        // Returning here closes TCP in the same instant as the
                        // final frame; a fast runner reports the close before
                        // onText, and the session ends with no complete.
                        while (s.read() != null) {
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })) {
            RecordingListener rec = (RecordingListener) l;
            SpeechRecognizer r = newRecognizer(rec, server.url());
            r.start();

            assertTrue(changeLatch.await(2, TimeUnit.SECONDS), "session should continue");
            assertEquals(1, rec.count("fail:" + ErrorCodes.READ_FAILED));

            r.stop();
            server.join(3000);
        }
    }

    /** A listener that calls stop() from a non-terminal callback. */
    static class StopFromChangeListener extends RecordingListener {
        volatile SpeechRecognizer recognizer;
        final CountDownLatch stoppedLatch = new CountDownLatch(1);
        volatile long stopDurationMs = -1;
        volatile ASRException stopError;

        @Override
        public void onRecognitionResultChange(SpeechRecognitionResponse r) {
            super.onRecognitionResultChange(r);
            long start = System.nanoTime();
            try {
                recognizer.stop();
            } catch (ASRException e) {
                stopError = e;
            }
            stopDurationMs = (System.nanoTime() - start) / 1_000_000;
            stoppedLatch.countDown();
        }
    }

    @Test
    void stopFromNonTerminalCallbackReturnsPromptly() throws Exception {
        StopFromChangeListener listener = new StopFromChangeListener();
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            try {
                s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\","
                        + "\"result\":{\"slice_type\":1,\"index\":0,\"voice_text_str\":\"partial\"}}");
                MiniWebSocketServer.Frame f;
                while ((f = s.read()) != null) {
                    if (f.isText() && f.text().equals("{\"type\":\"end\"}")) {
                        s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\",\"final\":1,"
                                + "\"result\":{\"slice_type\":2}}");
                        // Keep the socket up until the client disconnects.
                        // Returning here closes TCP in the same instant as the
                        // final frame; a fast runner reports the close before
                        // onText, and the session ends with no complete.
                        while (s.read() != null) {
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })) {
            SpeechRecognizer r = newRecognizer(listener, server.url());
            listener.recognizer = r;
            r.start();

            assertTrue(listener.stoppedLatch.await(3, TimeUnit.SECONDS),
                    "stop() inside onRecognitionResultChange did not return promptly (self-deadlock?)");
            assertTrue(listener.stopDurationMs < 2000,
                    "stop took " + listener.stopDurationMs + "ms, want prompt return");
            assertEquals(null, listener.stopError, "stop should succeed: " + listener.stopError);

            // The watchdog completes the session after the server replies final.
            assertTrue(listener.completeLatch.await(3, TimeUnit.SECONDS));
            server.join(3000);
        }
    }

    /** A listener that throws inside a callback; must not crash the SDK. */
    static class ThrowingListener extends RecordingListener {
        @Override
        public void onRecognitionResultChange(SpeechRecognitionResponse r) {
            throw new RuntimeException("listener boom");
        }
    }

    @Test
    void listenerExceptionIsRecoveredAndReported() throws Exception {
        ThrowingListener listener = new ThrowingListener();
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            try {
                s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\","
                        + "\"result\":{\"slice_type\":1,\"index\":0,\"voice_text_str\":\"hi\"}}");
                while (s.read() != null) {
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })) {
            SpeechRecognizer r = newRecognizer(listener, server.url());
            r.start();

            assertTrue(listener.failLatch.await(2, TimeUnit.SECONDS),
                    "panic should be surfaced via onFail");
            assertNotNull(listener.lastError);
            assertEquals(ErrorCodes.READ_FAILED, listener.lastError.getCode());
            assertTrue(listener.lastError.getMessage().contains("listener boom"),
                    listener.lastError.getMessage());

            // The recognizer is stopped after the panic; late writes fail.
            ASRException err = assertThrows(ASRException.class, () -> r.write(new byte[]{9}));
            assertEquals(ErrorCodes.NOT_STARTED, err.getCode());
            server.join(3000);
        }
    }

    @Test
    void stopTimesOutAndForceClosesWhenServerNeverFinishes() throws Exception {
        RecordingListener listener = new RecordingListener();
        AtomicBoolean serverGotEnd = new AtomicBoolean();
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            MiniWebSocketServer.Frame f;
            while ((f = s.read()) != null) {
                if (f.isText() && f.text().equals("{\"type\":\"end\"}")) {
                    serverGotEnd.set(true);
                    // Never reply with a final frame; the client must force-close.
                }
            }
        })) {
            SpeechRecognizer r = newRecognizer(listener, server.url());
            r.setStopTimeout(Duration.ofSeconds(1));
            r.start();

            long start = System.nanoTime();
            r.stop();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(serverGotEnd.get(), "server should have read the end signal");
            assertTrue(elapsedMs >= 1000 && elapsedMs < 5000,
                    "stop should wait ~stopTimeout, took " + elapsedMs + "ms");
            assertEquals(0, listener.count("complete:"));
            server.join(3000);
        }
    }

    @Test
    void externalStopWaitsForTerminalCallback() throws Exception {
        AtomicBoolean entered = new AtomicBoolean();
        AtomicBoolean release = new AtomicBoolean();
        SpeechRecognitionListener listener = new RecordingListener() {
            @Override
            public void onRecognitionComplete(SpeechRecognitionResponse r) {
                super.onRecognitionComplete(r);
                entered.set(true);
                while (!release.get()) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        };
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            try {
                MiniWebSocketServer.Frame f;
                while ((f = s.read()) != null) {
                    if (f.isText() && f.text().equals("{\"type\":\"end\"}")) {
                        s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\",\"final\":1,"
                                + "\"result\":{\"slice_type\":2}}");
                        // Keep the socket up until the client disconnects.
                        // Returning here closes TCP in the same instant as the
                        // final frame; a fast runner reports the close before
                        // onText, and the session ends with no complete.
                        while (s.read() != null) {
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })) {
            SpeechRecognizer r = newRecognizer(listener, server.url());
            r.start();

            AtomicReference<Thread> stopThread = new AtomicReference<>();
            AtomicBoolean stopReturned = new AtomicBoolean();
            Thread t = new Thread(() -> {
                try {
                    r.stop();
                } catch (ASRException ignored) {
                }
                stopReturned.set(true);
            });
            stopThread.set(t);
            t.start();

            // Wait until the terminal callback is running.
            long deadline = System.currentTimeMillis() + 3000;
            while (!entered.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(entered.get(), "terminal callback should be running");
            Thread.sleep(100);
            assertFalse(stopReturned.get(), "stop returned before terminal callback finished");

            release.set(true);
            t.join(3000);
            assertTrue(stopReturned.get(), "stop should return after the callback finishes");
            server.join(3000);
        }
    }

    /** Terminal frame arrives during stop()'s timed wait, but the terminal
     * callback runs LONGER than the stop timeout. stop() must not return
     * early: once the terminal response is in, it waits for the callbacks to
     * finish (mirrors the Go SDK's waitForCallbacksOrAbort terminal branch). */
    @Test
    void stopWaitsForSlowTerminalCallbackBeyondTimeout() throws Exception {
        AtomicBoolean entered = new AtomicBoolean();
        AtomicBoolean release = new AtomicBoolean();
        SpeechRecognitionListener listener = new RecordingListener() {
            @Override
            public void onRecognitionComplete(SpeechRecognitionResponse r) {
                super.onRecognitionComplete(r);
                entered.set(true);
                while (!release.get()) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        };
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            try {
                MiniWebSocketServer.Frame f;
                while ((f = s.read()) != null) {
                    if (f.isText() && f.text().equals("{\"type\":\"end\"}")) {
                        s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\",\"final\":1,"
                                + "\"result\":{\"slice_type\":2}}");
                        // Keep the socket up until the client disconnects.
                        // Returning here closes TCP in the same instant as the
                        // final frame; a fast runner reports the close before
                        // onText, and the session ends with no complete.
                        while (s.read() != null) {
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })) {
            SpeechRecognizer r = newRecognizer(listener, server.url());
            r.setStopTimeout(Duration.ofSeconds(1)); // callback outlives this
            r.start();

            AtomicBoolean stopReturned = new AtomicBoolean();
            AtomicReference<ASRException> stopError = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    r.stop();
                } catch (ASRException e) {
                    stopError.set(e);
                }
                stopReturned.set(true);
            });
            t.start();

            long deadline = System.currentTimeMillis() + 3000;
            while (!entered.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            boolean callbackRunning = entered.get();
            // Let the terminal callback exceed the stop timeout.
            Thread.sleep(1500);
            assertTrue(callbackRunning, "terminal callback should be running");
            assertFalse(stopReturned.get(),
                    "stop returned while the terminal callback was still running past stop timeout");

            release.set(true);
            t.join(3000);
            assertTrue(stopReturned.get());
            assertEquals(null, stopError.get());
            server.join(3000);
        }
    }

    /** If the server sends final immediately after the handshake, the state
     * must not be flipped back to RUNNING by start() and the start callback
     * must still precede the completion callback. */
    @Test
    void immediateFinalAfterHandshakeKeepsStoppedStateAndOrder() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            try {
                s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\",\"final\":1,"
                        + "\"result\":{\"slice_type\":2}}");
                while (s.read() != null) {
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })) {
            SpeechRecognizer r = newRecognizer(listener, server.url());
            r.start();

            assertTrue(listener.completeLatch.await(2, TimeUnit.SECONDS),
                    "should complete even when final arrives immediately");
            // Order: start must precede complete (voice_id is a generated
            // UUID here, so match by prefix).
            int startIdx = -1;
            int completeIdx = -1;
            for (int i = 0; i < listener.events.size(); i++) {
                String e = listener.events.get(i);
                if (e.startsWith("start:") && startIdx < 0) {
                    startIdx = i;
                }
                if (e.startsWith("complete:") && completeIdx < 0) {
                    completeIdx = i;
                }
            }
            assertTrue(startIdx >= 0, "missing start event: " + listener.events);
            assertTrue(completeIdx > startIdx,
                    "complete should follow start: " + listener.events);
            // The finished session must stay stopped.
            ASRException err = assertThrows(ASRException.class, () -> r.write(new byte[]{9}));
            assertEquals(ErrorCodes.NOT_STARTED, err.getCode());
            server.join(3000);
        }
    }

    @Test
    void reconnectRequiresNewInstance() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(holdOpenAnsweringEnd())) {
            SpeechRecognizer r = newRecognizer(new RecordingListener(), server.url());
            r.start();
            r.stop();
            ASRException err = assertThrows(ASRException.class, r::start);
            assertEquals(ErrorCodes.ALREADY_STARTED, err.getCode(), "single-use recognizer");
            server.join(3000);
        }
    }

    @Test
    void concurrentWritesAndStopDoNotDeadlock() throws Exception {
        AtomicBoolean gotEnd = new AtomicBoolean();
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            try {
                MiniWebSocketServer.Frame f;
                while ((f = s.read()) != null) {
                    if (f.isText() && f.text().equals("{\"type\":\"end\"}")) {
                        gotEnd.set(true);
                        s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\",\"final\":1,"
                                + "\"result\":{\"slice_type\":2}}");
                        // Keep the socket up until the client disconnects.
                        // Returning here closes TCP in the same instant as the
                        // final frame; a fast runner reports the close before
                        // onText, and the session ends with no complete.
                        while (s.read() != null) {
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })) {
            SpeechRecognizer r = newRecognizer(new RecordingListener(), server.url());
            r.start();
            final SpeechRecognizer shared = r;

            Thread[] writers = new Thread[4];
            for (int i = 0; i < writers.length; i++) {
                writers[i] = new Thread(() -> {
                    for (int j = 0; j < 25; j++) {
                        try {
                            shared.write(new byte[]{0, 1});
                        } catch (ASRException ignored) {
                            // Session may have been stopped concurrently.
                        }
                    }
                });
            }
            for (Thread w : writers) {
                w.start();
            }
            for (Thread w : writers) {
                w.join(3000);
            }
            r.stop();
            assertTrue(gotEnd.get());
            server.join(3000);
        }
    }
}
