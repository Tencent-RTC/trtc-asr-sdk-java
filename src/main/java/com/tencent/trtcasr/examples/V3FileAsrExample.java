package com.tencent.trtcasr.examples;

import java.nio.file.Files;
import java.nio.file.Path;

import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.v3.CreateTranscriptionRequest;
import com.tencent.trtcasr.v3.FileRecognizer;
import com.tencent.trtcasr.v3.TranscriptionStatus;
import com.tencent.trtcasr.v3.Wire;

/**
 * Async audio file recognition example over the v3 protocol
 * (POST /v3/create_transcription + /v3/describe_transcription).
 *
 * <p>Credentials come from environment variables:
 * {@code TRTC_ASR_SDK_APP_ID}, {@code TRTC_ASR_SECRET_KEY}
 * (v3 does not need the Tencent Cloud APPID.)
 *
 * <p>Usage: {@code java ... V3FileAsrExample <local.wav> [engine]} or
 * {@code java ... V3FileAsrExample -u <url> [engine]}
 */
public class V3FileAsrExample {
    public static void main(String[] args) throws Exception {
        long sdkAppId = Long.parseLong(env("TRTC_ASR_SDK_APP_ID", "0"));
        String secretKey = env("TRTC_ASR_SECRET_KEY", "");
        if (sdkAppId == 0 || secretKey.isEmpty()) {
            System.err.println("Set TRTC_ASR_SDK_APP_ID and TRTC_ASR_SECRET_KEY first.");
            System.exit(1);
        }
        if (args.length < 1) {
            System.err.println("Usage: V3FileAsrExample <local.wav> <engine> | V3FileAsrExample -u <url> <engine>");
            System.exit(1);
        }
        final boolean urlMode = args[0].equals("-u");
        // engine/lang default to the recommended bigmodel + zh pairing; an
        // empty lang falls back to server-side language detection.
        final int engineIdx = urlMode ? 2 : 1;
        if (args.length <= engineIdx) {
            System.err.println("error: engine is required (engine model type, e.g. bigmodel)");
            System.exit(2);
        }
        String engine = args[engineIdx];
        String lang = args.length > (urlMode ? 3 : 2) ? args[urlMode ? 3 : 2] : "";
        // The bigmodel engine is best used with an explicit language; every
        // other engine falls back to server-side detection unless lang is given.
        if (lang.isEmpty() && "bigmodel".equals(engine)) {
            lang = "zh";
        }

        // v3 credentials need only SDKAppID + SecretKey (no Tencent Cloud APPID).
        Credential credential = Wire.newCredential(sdkAppId, secretKey);
        FileRecognizer recognizer = new FileRecognizer(credential);

        CreateTranscriptionRequest req = new CreateTranscriptionRequest();
        req.engineModelType = engine;
        if (!lang.isEmpty()) {
            req.language = lang;
        }
        String taskId;
        if (urlMode) {
            req.sourceType = Wire.SOURCE_TYPE_URL;
            req.url = args[1];
            taskId = recognizer.createTask(req);
        } else {
            byte[] data = Files.readAllBytes(Path.of(args[0]));
            taskId = recognizer.createTaskFromDataWithOptions(data, req);
        }
        System.out.println("Task created: " + taskId);

        TranscriptionStatus status = recognizer.waitForResult(taskId);
        System.out.printf("Status: %s  Duration: %.2f s%n", status.statusStr, status.audioDuration);
        System.out.println("Result: " + status.result);
        if (status.resultDetail != null) {
            for (TranscriptionStatus.SentenceDetail d : status.resultDetail) {
                String speaker = !d.speakerRoleName.isEmpty() ? " [" + d.speakerRoleName + "]"
                        : d.speakerId > 0 ? " [spk" + d.speakerId + "]" : "";
                System.out.printf("  [%6d - %6d]%s %s%n", d.startMs, d.endMs, speaker,
                        d.finalSentence);
            }
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
