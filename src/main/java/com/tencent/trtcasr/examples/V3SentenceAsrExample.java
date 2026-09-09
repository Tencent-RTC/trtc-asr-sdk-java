package com.tencent.trtcasr.examples;

import java.nio.file.Files;
import java.nio.file.Path;

import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.v3.SentenceRecognizer;
import com.tencent.trtcasr.v3.TranscribeResponse;
import com.tencent.trtcasr.v3.Wire;

/**
 * Sentence (one-shot) speech recognition example over the v3 protocol
 * (POST /v3/transcribe).
 *
 * <p>Recognizes a local audio file (<=60s, <=3MB).
 *
 * <p>Credentials come from environment variables:
 * {@code TRTC_ASR_SDK_APP_ID}, {@code TRTC_ASR_SECRET_KEY}
 * (v3 does not need the Tencent Cloud APPID.)
 *
 * <p>Prerequisite: the server has enabled the EnableV3Route gray switch for
 * your SDKAppID, otherwise requests fail with 404/4001.
 *
 * <p>Usage: {@code java ... V3SentenceAsrExample <audio.pcm> [engine]}
 */
public class V3SentenceAsrExample {
    public static void main(String[] args) throws Exception {
        long sdkAppId = Long.parseLong(env("TRTC_ASR_SDK_APP_ID", "0"));
        String secretKey = env("TRTC_ASR_SECRET_KEY", "");
        if (sdkAppId == 0 || secretKey.isEmpty()) {
            System.err.println("Set TRTC_ASR_SDK_APP_ID and TRTC_ASR_SECRET_KEY first.");
            System.exit(1);
        }
        String path = args.length > 0 ? args[0] : "audio.pcm";
        String engine = args.length > 1 ? args[1] : "16k_zh_en";

        // v3 credentials need only SDKAppID + SecretKey (no Tencent Cloud APPID).
        Credential credential = Wire.newCredential(sdkAppId, secretKey);
        SentenceRecognizer recognizer = new SentenceRecognizer(credential);

        byte[] data = Files.readAllBytes(Path.of(path));
        TranscribeResponse resp = recognizer.recognizeData(data, "pcm", engine);

        System.out.println("Result: " + resp.result);
        System.out.println("Duration: " + resp.audioDuration + " ms  RequestId: " + resp.requestId);
        if (resp.wordList != null) {
            for (TranscribeResponse.Word w : resp.wordList) {
                System.out.printf("  [%6d - %6d] %s%n", w.startTime, w.endTime, w.word);
            }
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
