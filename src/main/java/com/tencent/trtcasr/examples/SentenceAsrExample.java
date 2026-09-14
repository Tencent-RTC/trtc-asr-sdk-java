package com.tencent.trtcasr.examples;

import java.nio.file.Files;
import java.nio.file.Path;

import com.tencent.trtcasr.asr.SentenceRecognizer;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.Credential;

/**
 * One-shot sentence recognition example (audio <= 60s / 3MB).
 *
 * <p>Credentials come from environment variables: TRTC_ASR_APP_ID,
 * TRTC_ASR_SDK_APP_ID, TRTC_ASR_SECRET_KEY.
 *
 * <p>Usage: SentenceAsrExample &lt;audio.pcm&gt; [format=pcm]
 * [engine=bigmodel] [lang=zh]
 */
public class SentenceAsrExample {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: SentenceAsrExample <audio-file> [format=pcm]"
                    + " <engine> [lang]");
            System.exit(1);
        }
        String path = args[0];
        String format = args.length > 1 ? args[1] : "pcm";
        if (args.length <= 2) {
            System.err.println("error: engine is required (engine model type, e.g. bigmodel)");
            System.exit(2);
        }
        String engine = args[2];
        // Empty lang falls back to server-side language detection.
        String lang = args.length > 3 ? args[3] : "";
        // The bigmodel engine is best used with an explicit language; every
        // other engine falls back to server-side detection unless lang is given.
        if (lang.isEmpty() && "bigmodel".equals(engine)) {
            lang = "zh";
        }

        Credential credential = new Credential(
                Long.parseLong(env("TRTC_ASR_APP_ID")),
                Long.parseLong(env("TRTC_ASR_SDK_APP_ID")),
                env("TRTC_ASR_SECRET_KEY"));

        byte[] data = Files.readAllBytes(Path.of(path));
        SentenceRecognizer recognizer = new SentenceRecognizer(credential);
        try {
            SentenceRecognizer.SentenceRecognitionRequest req =
                    new SentenceRecognizer.SentenceRecognitionRequest();
            req.setEngServiceType(engine);
            req.setVoiceFormat(format);
            if (!lang.isEmpty()) {
                req.setLanguage(lang);
            }
            var result = recognizer.recognizeDataWithOptions(data, req);
            System.out.println("识别结果: " + result.getResult());
            System.out.println("音频时长: " + result.getAudioDuration() + " ms");
        } catch (ASRException e) {
            System.err.println("识别失败: " + e.getMessage());
            System.exit(1);
        }
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
