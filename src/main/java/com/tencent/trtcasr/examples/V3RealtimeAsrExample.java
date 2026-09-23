package com.tencent.trtcasr.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.tencent.trtcasr.asr.SpeechRecognitionListener;
import com.tencent.trtcasr.asr.SpeechRecognitionResponse;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.v3.SpeechRecognizer;
import com.tencent.trtcasr.v3.Wire;

/**
 * Realtime speech recognition example over the v3 protocol (/asr/v3).
 *
 * <p>Reads a PCM file (16kHz 16bit mono) and streams it in 200ms chunks.
 *
 * <p>Credentials come from environment variables:
 * {@code TRTC_ASR_SDK_APP_ID}, {@code TRTC_ASR_SECRET_KEY}
 * (v3 does not need the Tencent Cloud APPID.)
 *
 * <p>Usage: {@code java ... V3RealtimeAsrExample <audio.pcm> [engine] [lang] [speakerContext]
 * [speakerContextId]}
 *
 * <p>Speaker diarization can be made resumable across connections
 * ("断点续传"): keep the printed speaker_context_id and pass it back on the
 * next connection, e.g.
 * {@code V3RealtimeAsrExample test.pcm bigmodel zh 1} then
 * {@code V3RealtimeAsrExample test.pcm bigmodel zh 1 <speaker_context_id>}.
 */
public class V3RealtimeAsrExample {
    private static final SpeechRecognitionListener LISTENER = new SpeechRecognitionListener() {
        @Override
        public void onRecognitionStart(SpeechRecognitionResponse response) {
            System.out.println("[start] voice_id=" + response.getVoiceId());
            if (response.getSpeakerContinue() != null) {
                System.out.println("speaker context: status=\""
                        + response.getSpeakerContinue().getContinueStatus()
                        + "\" id=" + response.getSpeakerContinue().getSpeakerContextId());
            }
        }

        @Override
        public void onSentenceEnd(SpeechRecognitionResponse response) {
            System.out.println("[end] " + response.getResult().getVoiceTextStr());
            for (SpeechRecognitionResponse.SpeakerSegment seg : response.getResult().getSpeakerSegments()) {
                String label = seg.getSpeakerName() == null || seg.getSpeakerName().isEmpty()
                        ? "spk" + seg.getSpeakerId() : seg.getSpeakerName();
                System.out.println("  [" + label + "] " + seg.getText()
                        + " (" + seg.getStartTime() + "-" + seg.getEndTime() + " ms)");
            }
        }

        @Override
        public void onRecognitionResultChange(SpeechRecognitionResponse response) {
            System.out.println("[change] " + response.getResult().getVoiceTextStr());
        }

        @Override
        public void onRecognitionComplete(SpeechRecognitionResponse response) {
            System.out.println("[complete]");
        }

        @Override
        public void onFail(SpeechRecognitionResponse response, ASRException error) {
            System.err.println("[fail] " + error);
        }
    };

    public static void main(String[] args) throws Exception {
        long sdkAppId = Long.parseLong(env("TRTC_ASR_SDK_APP_ID", "0"));
        String secretKey = env("TRTC_ASR_SECRET_KEY", "");
        if (sdkAppId == 0 || secretKey.isEmpty()) {
            System.err.println("Set TRTC_ASR_SDK_APP_ID and TRTC_ASR_SECRET_KEY first.");
            System.exit(1);
        }
        String path = args.length > 0 ? args[0] : "examples/test.pcm";
        if (args.length <= 1) {
            System.err.println("error: engine is required (engine model type, e.g. bigmodel)");
            System.exit(2);
        }
        String engine = args[1];
        // Empty lang falls back to server-side language detection.
        String lang = args.length > 2 ? args[2] : "";
        // The bigmodel engine is best used with an explicit language; every
        // other engine falls back to server-side detection unless lang is given.
        if (lang.isEmpty() && "bigmodel".equals(engine)) {
            lang = "zh";
        }

        // v3 credentials need only SDKAppID + SecretKey (no Tencent Cloud APPID).
        Credential credential = Wire.newCredential(sdkAppId, secretKey);
        // arg[3]: resumable speaker diarization (0 off / 1 sync / 2 async);
        // arg[4]: the speaker_context_id printed by an earlier run.
        int speakerContext = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        String speakerContextId = args.length > 4 ? args[4] : "";

        SpeechRecognizer recognizer = new SpeechRecognizer(credential, engine, LISTENER);
        if (!lang.isEmpty()) {
            recognizer.setLanguage(lang); // bigmodel 建议显式指定语种
        }
        // Speaker-context persistence requires speaker diarization; the SDK
        // rejects the combination locally otherwise.
        if (speakerContext != 0) {
            recognizer.setSpeakerDiarization(Wire.SPEAKER_DIARIZATION_CLUSTER);
            recognizer.setEnableSpeakerContext(speakerContext);
        }
        if (!speakerContextId.isEmpty()) {
            recognizer.setSpeakerContextId(speakerContextId);
        }
        recognizer.setWriteTimeout(Duration.ofSeconds(5));
        recognizer.setStopTimeout(Duration.ofSeconds(10));

        // start() waits synchronously for the server's ack: authentication
        // (4002) and gray-switch (4001) errors are thrown here, not via onFail.
        // While resuming a speaker context the ack arrives once the server has
        // applied the stored snapshot.
        recognizer.start();

        // The handshake result is also available without a callback. Persist
        // the id: passing it back keeps speaker ids stable across connections.
        if (recognizer.getSpeakerContinue() != null) {
            System.out.println("speaker context id: "
                    + recognizer.getSpeakerContinue().getSpeakerContextId()
                    + " (status: \"" + recognizer.getSpeakerContinue().getContinueStatus()
                    + "\") — reuse it as the 5th argument");
        }

        byte[] data = Files.readAllBytes(Path.of(path));
        final int sliceSize = 6400; // 200ms of 16kHz 16bit mono PCM
        // The server rate-limits to at most 3s of audio per 1s wall-clock
        // (error 4000): keep the pacing when enlarging the buffer.
        for (int offset = 0; offset < data.length; offset += sliceSize) {
            int len = Math.min(sliceSize, data.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(data, offset, chunk, 0, len);
            recognizer.write(chunk);
            Thread.sleep(200);
        }

        recognizer.stop();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
