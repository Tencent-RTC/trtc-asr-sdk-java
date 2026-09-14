package com.tencent.trtcasr.examples;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.tencent.trtcasr.asr.SpeechRecognitionListener;
import com.tencent.trtcasr.asr.SpeechRecognitionResponse;
import com.tencent.trtcasr.asr.SpeechRecognizer;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.Credential;

/**
 * Realtime speech recognition example.
 *
 * <p>Reads a PCM file (16kHz 16bit mono) and streams it in 200ms chunks.
 *
 * <p>Credentials come from environment variables: TRTC_ASR_APP_ID,
 * TRTC_ASR_SDK_APP_ID, TRTC_ASR_SECRET_KEY.
 *
 * <p>Usage: RealtimeAsrExample &lt;audio.pcm&gt; [engine_model_type]
 */
public class RealtimeAsrExample {

    static class Printer implements SpeechRecognitionListener {
        @Override
        public void onRecognitionStart(SpeechRecognitionResponse r) {
            System.out.println("[start] voice_id=" + r.getVoiceId());
        }

        @Override
        public void onSentenceBegin(SpeechRecognitionResponse r) {
            System.out.println("[begin] index=" + r.getResult().getIndex());
        }

        @Override
        public void onRecognitionResultChange(SpeechRecognitionResponse r) {
            System.out.println("[change] " + r.getResult().getVoiceTextStr());
        }

        @Override
        public void onSentenceEnd(SpeechRecognitionResponse r) {
            System.out.println("[end] index=" + r.getResult().getIndex() + " text="
                    + r.getResult().getVoiceTextStr() + " (" + r.getResult().getStartTime()
                    + "-" + r.getResult().getEndTime() + "ms)");
            for (var seg : r.getResult().getSpeakerSegments()) {
                String name = seg.getSpeakerName().isEmpty()
                        ? "spk" + seg.getSpeakerId()
                        : seg.getSpeakerName();
                System.out.println("       [" + name + "] " + seg.getText() + " ("
                        + seg.getStartTime() + "-" + seg.getEndTime() + "ms)");
            }
        }

        @Override
        public void onRecognitionComplete(SpeechRecognitionResponse r) {
            System.out.println("[complete] voice_id=" + r.getVoiceId());
        }

        @Override
        public void onFail(SpeechRecognitionResponse r, ASRException e) {
            System.err.println("[fail] " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: RealtimeAsrExample <audio.pcm> <engine> [lang]");
            System.exit(1);
        }
        String path = args[0];
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

        Credential credential = new Credential(
                Long.parseLong(env("TRTC_ASR_APP_ID")),
                Long.parseLong(env("TRTC_ASR_SDK_APP_ID")),
                env("TRTC_ASR_SECRET_KEY"));

        SpeechRecognizer recognizer = new SpeechRecognizer(credential, engine, new Printer());
        if (!lang.isEmpty()) {
            recognizer.setLanguage(lang); // bigmodel 建议显式指定语种
        }
        // recognizer.setSpeakerDiarization(SignatureParams.SPEAKER_DIARIZATION_CLUSTER);
        // recognizer.setWordInfo(1);
        recognizer.start();

        try (InputStream in = Files.newInputStream(Path.of(path))) {
            byte[] buf = new byte[6400]; // 200ms of 16kHz 16bit mono PCM
            int n;
            while ((n = in.read(buf)) > 0) {
                byte[] chunk = n == buf.length ? buf : java.util.Arrays.copyOf(buf, n);
                try {
                    recognizer.write(chunk);
                } catch (ASRException e) {
                    System.err.println("write failed: " + e.getMessage());
                    break;
                }
                Thread.sleep(200); // simulate realtime
            }
        } catch (IOException e) {
            System.err.println("read audio failed: " + e.getMessage());
        }

        recognizer.stop();
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null) {
            System.err.println("missing env var: " + name);
            System.exit(1);
        }
        return v;
    }
}
